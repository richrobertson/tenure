package com.richrobertson.tenure

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import cats.syntax.semigroupk.*
import com.richrobertson.tenure.api.LeaseRoutes
import com.richrobertson.tenure.group.{GroupId, GroupRuntime}
import com.richrobertson.tenure.observability.{Observability, ObservabilityRoutes}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, RaftNode}
import com.richrobertson.tenure.runtime.StartupValidation
import com.richrobertson.tenure.routing.HashRouter
import com.richrobertson.tenure.service.LeaseService
import com.richrobertson.tenure.service.ServiceCodecs.given
import com.richrobertson.tenure.time.Clock
import io.circe.parser.decode
import com.comcast.ip4s.{Host, Port}
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
      case Nil                              => runLocal(1).as(ExitCode.Success)
      case "--local-groups" :: count :: Nil => parseLocalGroupCount(count).flatMap(runLocal).as(ExitCode.Success)
      case configPath :: Nil => runClustered(configPath).as(ExitCode.Success)
      case _ => IO.println("usage: sbt run                          # single-group local prototype\n   or: sbt \"run -- --local-groups <n>\"  # local multi-group simulation\n   or: sbt \"run -- <config-path>\"       # clustered single-group mode").as(ExitCode.Error)

  private def runLocal(groupCount: Int): IO[Unit] =
    for
      _ <- IO.println(s"starting local prototype with $groupCount logical group(s) on $localApiHost:$localApiPort")
      _ <- localAppResource(groupCount).use(_ => IO.never)
    yield ()

  private def localAppResource(groupCount: Int): Resource[IO, Unit] =
    for
      observability <- Resource.eval(Observability.inMemory[IO])
      groupIds = (1 to groupCount).toVector.map(idx => GroupId(s"group-$idx"))
      groups <- Resource.eval(groupIds.toList.traverse(groupId => GroupRuntime.inMemory[IO](groupId, Clock.system[IO], observability = observability)))
      router = HashRouter(groupIds)
      service <- Resource.eval(LeaseService.routed[IO](router, groups, Clock.system[IO], observability = observability))
      host <- Resource.eval(parseHost(localApiHost, context = "single-node API host"))
      port <- Resource.eval(parsePort(localApiPort, context = "single-node API port"))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp(service, observability.snapshot, debugEnabled = isLoopback(localApiHost)))
        .build
    yield ()

  private def runClustered(configPath: String): IO[Unit] =
    for
      config <- loadConfig(configPath).flatMap(StartupValidation.validateClusteredConfig[IO])
      _ <- appResource(config).use(_ => IO.never)
    yield ()

  private def appResource(config: ClusterConfig): Resource[IO, Unit] =
    for
      observability <- Resource.eval(Observability.inMemory[IO])
      groupObservability = Observability.scoped(observability, Map("group_id" -> GroupId.Default.value), Map("group_id" -> GroupId.Default.value))
      persistence <- Resource.eval(RaftPersistence.fileBacked[IO](config.dataDir, config.nodeId, groupObservability))
      raftNode <- RaftNode.resource[IO](config, persistence, observability = groupObservability)
      group = GroupRuntime.replicated[IO](GroupId.Default, raftNode, Clock.system[IO], observability)
      service <- Resource.eval(LeaseService.routed[IO](HashRouter(Vector(GroupId.Default)), List(group), Clock.system[IO], observability = observability))
      host <- Resource.eval(parseHost(config.apiHost, context = s"cluster API host for node ${config.nodeId}", fallback = Some("127.0.0.1")))
      port <- Resource.eval(parsePort(config.apiPort, context = s"cluster API port for node ${config.nodeId}"))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp(service, observability.snapshot, isLoopback(config.apiHost)))
        .build
    yield ()

  private def httpApp(
      service: LeaseService[IO],
      snapshot: IO[com.richrobertson.tenure.observability.ObservabilitySnapshot],
      debugEnabled: Boolean
  ): HttpApp[IO] =
    val baseRoutes = LeaseRoutes.routes[IO](service)
    val debugRoutes = if debugEnabled then ObservabilityRoutes.routes[IO](snapshot) else org.http4s.HttpRoutes.empty[IO]
    (baseRoutes <+> debugRoutes).orNotFound

  private def parseLocalGroupCount(raw: String): IO[Int] =
    IO
      .fromEither(Either.catchNonFatal(raw.toInt).leftMap(_ => new IllegalArgumentException(s"invalid local group count: '$raw'")))
      .flatMap(count => IO.raiseUnless(count > 0)(new IllegalArgumentException("local group count must be positive")).as(count))

  private def isLoopback(host: String): Boolean =
    val normalized = host.trim.toLowerCase
    normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1"

  private def parseHost(raw: String, context: String, fallback: Option[String] = None): IO[Host] =
    Host.fromString(raw).orElse(fallback.flatMap(Host.fromString)) match
      case Some(host) => IO.pure(host)
      case None       => IO.raiseError(new IllegalArgumentException(s"invalid $context: '$raw'"))

  private def parsePort(raw: Int, context: String): IO[Port] =
    Port.fromInt(raw) match
      case Some(port) => IO.pure(port)
      case None       => IO.raiseError(new IllegalArgumentException(s"invalid $context: $raw"))

  private def loadConfig(path: String): IO[ClusterConfig] =
    IO.blocking(Files.readString(Paths.get(path), StandardCharsets.UTF_8)).flatMap(raw => IO.fromEither(decode[ClusterConfig](raw)))
