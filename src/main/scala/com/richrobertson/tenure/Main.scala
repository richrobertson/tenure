package com.richrobertson.tenure

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.semigroupk.*
import com.richrobertson.tenure.api.LeaseRoutes
import com.richrobertson.tenure.observability.{Observability, ObservabilityRoutes}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, RaftNode}
import com.richrobertson.tenure.service.LeaseService
import com.richrobertson.tenure.service.ServiceCodecs.given
import com.richrobertson.tenure.time.Clock
import io.circe.parser.decode
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object Main extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    args match
      case configPath :: Nil => runClustered(configPath).as(ExitCode.Success)
      case _                 => IO.println("usage: sbt 'run -- <config-path>'").as(ExitCode.Error)

  private def runClustered(configPath: String): IO[Unit] =
    for
      config <- loadConfig(configPath)
      _ <- appResource(config).use(_ => IO.never)
    yield ()

  private def appResource(config: ClusterConfig): Resource[IO, Unit] =
    for
      observability <- Resource.eval(Observability.inMemory[IO])
      persistence <- Resource.eval(RaftPersistence.fileBacked[IO](config.dataDir, config.nodeId, observability = observability))
      raftNode <- RaftNode.resource[IO](config, persistence, observability = observability)
      service = LeaseService.replicated[IO](raftNode, Clock.system[IO], observability = observability)
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(com.comcast.ip4s.Host.fromString(config.apiHost).getOrElse(com.comcast.ip4s.Host.fromString("127.0.0.1").get))
        .withPort(com.comcast.ip4s.Port.fromInt(config.apiPort).get)
        .withHttpApp((LeaseRoutes.routes[IO](service) <+> ObservabilityRoutes.routes[IO](observability.snapshot)).orNotFound)
        .build
    yield ()

  private def loadConfig(path: String): IO[ClusterConfig] =
    IO.blocking(Files.readString(Paths.get(path), StandardCharsets.UTF_8)).flatMap(raw => IO.fromEither(decode[ClusterConfig](raw)))
