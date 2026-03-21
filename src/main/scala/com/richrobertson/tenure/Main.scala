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
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object Main extends IOApp:
  private val localApiHost = "0.0.0.0"
  private val localApiPort = 8080

  override def run(args: List[String]): IO[ExitCode] =
    args match
      case Nil               => runLocal.as(ExitCode.Success)
      case configPath :: Nil => runClustered(configPath).as(ExitCode.Success)
      case _                 => IO.println("usage: sbt run            # single-node local prototype\n   or: sbt 'run -- <config-path>'  # clustered mode").as(ExitCode.Error)

  private def runLocal: IO[Unit] =
    for
      _ <- IO.println(s"starting single-node prototype on $localApiHost:$localApiPort")
      _ <- localAppResource.use(_ => IO.never)
    yield ()

  private def localAppResource: Resource[IO, Unit] =
    for
      service <- Resource.eval(LeaseService.inMemory[IO](Clock.system[IO]))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(com.comcast.ip4s.Host.fromString(localApiHost).get)
        .withPort(com.comcast.ip4s.Port.fromInt(localApiPort).get)
        .withHttpApp(LeaseRoutes.routes[IO](service).orNotFound)
        .build
    yield ()

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
        .withHttpApp(httpApp(config, service, observability.snapshot))
        .build
    yield ()

  private def httpApp(config: ClusterConfig, service: LeaseService[IO], snapshot: IO[com.richrobertson.tenure.observability.ObservabilitySnapshot]): HttpApp[IO] =
    val baseRoutes = LeaseRoutes.routes[IO](service)
    val debugRoutes = if isLoopback(config.apiHost) then ObservabilityRoutes.routes[IO](snapshot) else org.http4s.HttpRoutes.empty[IO]
    (baseRoutes <+> debugRoutes).orNotFound

  private def isLoopback(host: String): Boolean =
    val normalized = host.trim.toLowerCase
    normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1"

  private def loadConfig(path: String): IO[ClusterConfig] =
    IO.blocking(Files.readString(Paths.get(path), StandardCharsets.UTF_8)).flatMap(raw => IO.fromEither(decode[ClusterConfig](raw)))
