package com.richrobertson.tenure.runtime

import cats.effect.IO
import com.richrobertson.tenure.observability.Observability
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, PeerNode}
import com.richrobertson.tenure.service.ServiceCodecs.given
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class StartupValidationSpec extends CatsEffectSuite:
  test("cluster config rejects duplicate peer ids and duplicate local peer entries") {
    val config = ClusterConfig(
      nodeId = "node-1",
      apiHost = "127.0.0.1",
      apiPort = 9101,
      peers = List(
        PeerNode("node-1", "127.0.0.1", 9001, "127.0.0.1", 9101),
        PeerNode("node-1", "127.0.0.1", 9002, "127.0.0.1", 9102)
      ),
      dataDir = "/tmp/tenure-startup-validation-a"
    )

    val result = StartupValidation.validateConfig(config)
    assert(result.left.exists(error =>
      error.getMessage.contains("peer node ids must be unique") ||
        error.getMessage.contains("expected exactly one peer entry for local node")
    ))
  }

  test("cluster config rejects DNS hostnames and mismatched local api endpoint") {
    val config = ClusterConfig(
      nodeId = "node-1",
      apiHost = "127.0.0.1",
      apiPort = 9101,
      peers = List(
        PeerNode("node-1", "tenure.internal", 9001, "127.0.0.1", 9201)
      ),
      dataDir = "/tmp/tenure-startup-validation-b"
    )

    val result = StartupValidation.validateConfig(config)
    assert(result.left.exists(error =>
      error.getMessage.contains("must be an explicit IP or localhost") ||
        error.getMessage.contains("must match local peer apiPort")
    ))
  }

  test("cluster config rejects host values with embedded ports") {
    val config = ClusterConfig(
      nodeId = "node-1",
      apiHost = "127.0.0.1",
      apiPort = 9101,
      peers = List(
        PeerNode("node-1", "127.0.0.1:9001", 9001, "127.0.0.1", 9101)
      ),
      dataDir = "/tmp/tenure-startup-validation-d"
    )

    val result = StartupValidation.validateConfig(config)
    assert(result.left.exists(_.getMessage.contains("must be an explicit IP or localhost")))
  }

  test("cluster config rejects multiplexed raft and api ports") {
    val config = ClusterConfig(
      nodeId = "node-1",
      apiHost = "127.0.0.1",
      apiPort = 9101,
      peers = List(
        PeerNode("node-1", "127.0.0.1", 9101, "127.0.0.1", 9101)
      ),
      dataDir = "/tmp/tenure-startup-validation-c"
    )

    val result = StartupValidation.validateConfig(config)
    assert(result.left.exists(_.getMessage.contains("must use different raft and API ports")))
  }

  test("data directory validation rejects file paths") {
    IO.blocking(Files.createTempFile("tenure-startup-validation", ".txt")).flatMap { file =>
      StartupValidation.validateDataDir[IO](file.toString, "demo dataDir").attempt.map { result =>
        assert(result.left.exists(_.getMessage.contains("must be a directory path")))
      }
    }
  }

  test("file-backed persistence rejects incompatible node ownership") {
    IO.blocking(Files.createTempDirectory("tenure-startup-node-id")).flatMap { root =>
      val marker = root.resolve("node-id")
      IO.blocking(Files.writeString(marker, "node-a\n", StandardCharsets.UTF_8)) *>
        RaftPersistence.fileBacked[IO](root.toString, "node-b", Observability.noop[IO]).attempt.map { result =>
          assert(result.left.exists(_.getMessage.contains("belongs to node 'node-a'")))
        }
    }
  }

  test("file-backed persistence rejects empty node ownership markers") {
    IO.blocking(Files.createTempDirectory("tenure-startup-empty-node-id")).flatMap { root =>
      val marker = root.resolve("node-id")
      IO.blocking(Files.writeString(marker, "   \n", StandardCharsets.UTF_8)) *>
        RaftPersistence.fileBacked[IO](root.toString, "node-a", Observability.noop[IO]).attempt.map { result =>
          assert(result.left.exists(_.getMessage.contains("empty 'node-id' marker")))
        }
    }
  }

  test("file-backed persistence rejects directory/file shape conflicts") {
    IO.blocking(Files.createTempDirectory("tenure-startup-shape")).flatMap { root =>
      IO.blocking(Files.createDirectories(root.resolve("metadata.json"))) *>
        RaftPersistence.fileBacked[IO](root.toString, "node-a", Observability.noop[IO]).attempt.map { result =>
          assert(result.left.exists(_.getMessage.contains("persisted path must be a file")))
        }
    }
  }
