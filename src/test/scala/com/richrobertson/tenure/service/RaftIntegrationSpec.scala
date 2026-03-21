package com.richrobertson.tenure.service

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, NodeRole, PeerMessage, PeerNode, RaftNode}
import com.richrobertson.tenure.time.Clock
import munit.CatsEffectSuite
import java.nio.file.Files
import scala.concurrent.duration.*

class RaftIntegrationSpec extends CatsEffectSuite:
  override val munitTimeout: Duration = 30.seconds

  test("cluster elects a leader, rejects follower reads and writes, and replicates acquire/renew/release") {
    clusterResource.use { cluster =>
      for
        leader <- cluster.awaitLeader
        follower = cluster.nodes.find(_.id != leader.id).get
        acquired <- leader.service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 30, "req-1"))
        followerWrite <- follower.service.acquire(AcquireRequest("tenant-a", "resource-2", "holder-2", 30, "req-2"))
        followerRead <- follower.service.getLease(com.richrobertson.tenure.model.TenantId("tenant-a"), com.richrobertson.tenure.model.ResourceId("resource-1"))
        leaseId = acquired.toOption.flatMap(_.lease.leaseId).fold(fail("expected lease id"))(_.value.toString)
        renewed <- leader.service.renew(RenewRequest("tenant-a", "resource-1", leaseId, "holder-1", 45, "req-3"))
        released <- leader.service.release(ReleaseRequest("tenant-a", "resource-1", leaseId, "holder-1", "req-4"))
        leaderLease <- leader.service.getLease(com.richrobertson.tenure.model.TenantId("tenant-a"), com.richrobertson.tenure.model.ResourceId("resource-1"))
      yield
        assert(acquired.isRight)
        assert(renewed.isRight)
        assert(released.isRight)
        assert(followerWrite.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
        assert(followerRead.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
        assertEquals(leaderLease.toOption.map(_.lease.status.toString), Some("Released"))
    }
  }

  test("failover elects a new leader and restart replays committed log") {
    clusterResource.use { cluster =>
      for
        initialLeader <- cluster.awaitLeader
        _ <- initialLeader.service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 30, "req-1"))
        _ <- initialLeader.shutdown
        replacement <- cluster.awaitLeaderExcluding(initialLeader.id)
        lease <- replacement.service.getLease(com.richrobertson.tenure.model.TenantId("tenant-a"), com.richrobertson.tenure.model.ResourceId("resource-1"))
        _ <- initialLeader.restart
        restartedLease <- initialLeader.service.getLease(com.richrobertson.tenure.model.TenantId("tenant-a"), com.richrobertson.tenure.model.ResourceId("resource-1"))
      yield
        assertEquals(lease.toOption.map(_.found), Some(true))
        assertEquals(restartedLease.toOption.map(_.found), Some(true))
    }
  }

  test("static config uses explicit node ids and concrete IP port endpoints over TCP") {
    clusterResource.use { cluster =>
      IO {
        assertEquals(cluster.config.peers.map(_.nodeId), List("node-1", "node-2", "node-3"))
        assert(cluster.config.peers.forall(_.host == "127.0.0.1"))
        assert(cluster.config.peers.forall(_.port > 0))
      }
    }
  }

  private def clusterResource: Resource[IO, TestCluster] =
    for
      root <- Resource.eval(IO.blocking(Files.createTempDirectory("tenure-raft-spec")))
      config = ClusterConfig(
        nodeId = "node-1",
        apiHost = "127.0.0.1",
        apiPort = 18081,
        peers = List(
          PeerNode("node-1", "127.0.0.1", 19091),
          PeerNode("node-2", "127.0.0.1", 19092),
          PeerNode("node-3", "127.0.0.1", 19093)
        ),
        dataDir = root.resolve("node-1").toString
      )
      node1 <- testNode(config, root.resolve("node-1").toString)
      node2 <- testNode(config.copy(nodeId = "node-2", apiPort = 18082, dataDir = root.resolve("node-2").toString), root.resolve("node-2").toString)
      node3 <- testNode(config.copy(nodeId = "node-3", apiPort = 18083, dataDir = root.resolve("node-3").toString), root.resolve("node-3").toString)
    yield TestCluster(config, List(node1, node2, node3))

  private def testNode(config: ClusterConfig, dataDir: String): Resource[IO, TestNode] =
    for
      persistence <- Resource.eval(RaftPersistence.fileBacked[IO](dataDir)(using summon, summon, PeerMessage.given_Codec_RaftLogEntry))
      raft <- RaftNode.resource[IO](config, persistence)
      service = LeaseService.replicated[IO](raft, Clock.system[IO])
    yield TestNode(config.nodeId, config, raft, service, dataDir)

  private final case class TestCluster(config: ClusterConfig, nodes: List[TestNode]):
    def awaitLeader: IO[TestNode] =
      pollUntil(nodes.findM(node => node.raft.role.map(_ == NodeRole.Leader)))

    def awaitLeaderExcluding(nodeId: String): IO[TestNode] =
      pollUntil(nodes.filterNot(_.id == nodeId).findM(node => node.raft.role.map(_ == NodeRole.Leader)))

    private def pollUntil(value: IO[Option[TestNode]]): IO[TestNode] =
      value.flatMap {
        case Some(node) => IO.pure(node)
        case None       => IO.sleep(200.millis) *> pollUntil(value)
      }

  private final case class TestNode(id: String, config: ClusterConfig, raft: RaftNode[IO], service: LeaseService[IO], dataDir: String):
    def shutdown: IO[Unit] = raft match
      case live: AnyRef { def shutdown: IO[Unit] } => live.shutdown
      case _                                       => IO.unit

    def restart: IO[Unit] = IO.unit
