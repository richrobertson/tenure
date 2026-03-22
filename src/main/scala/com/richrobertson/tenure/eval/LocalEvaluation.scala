package com.richrobertson.tenure.eval

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import cats.syntax.parallel.*
import com.richrobertson.tenure.auth.Principal
import com.richrobertson.tenure.model.{LeaseStatus, ResourceId, TenantId}
import com.richrobertson.tenure.observability.{InMemoryObservability, Observability}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, NodeRole, PeerNode, RaftNode}
import com.richrobertson.tenure.service.*
import com.richrobertson.tenure.service.ServiceCodecs.given
import com.richrobertson.tenure.testkit.{ControlledFailureInjector, FailureInjector, FailurePoint}
import com.richrobertson.tenure.time.Clock
import io.circe.Encoder
import io.circe.generic.semiauto.*
import io.circe.syntax.*

import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*

object LocalEvaluation extends IOApp:
  private val LocalHost = "127.0.0.1"

  override def run(args: List[String]): IO[ExitCode] =
    parseArgs(args).fold(
      error => IO.println(error).as(ExitCode.Error),
      command =>
        execute(command).flatMap { result =>
          command.output match
            case Some(path) =>
              writeOutput(path, result.json).as(if result.success then ExitCode.Success else ExitCode.Error)
            case None =>
              IO.println(result.json).as(if result.success then ExitCode.Success else ExitCode.Error)
        }
    )

  private def execute(command: Command): IO[EvaluationResult] =
    command match
      case demo: DemoCommand =>
        runDemo(demo).map(report => EvaluationResult(report.asJson.spaces2, report.scenarios.forall(_.success)))
      case benchmark: BenchmarkCommand =>
        runBenchmark(benchmark).map(report => EvaluationResult(report.asJson.spaces2, success = true))

  def runDemo(command: DemoCommand = DemoCommand()): IO[DemoReport] =
    prepareRoot(command.workDir).flatMap(root => withCluster(root).use { cluster =>
      val tenantId = TenantId("demo-tenant")
      val principal = Principal("demo-user", tenantId)
      val resourceId = ResourceId("lease-demo")
      val retryResourceId = ResourceId("retry-demo")
      val fencingResourceId = ResourceId("fencing-demo")
      val delayedResourceId = ResourceId("delay-demo")

      for
        startedAt <- now
        leader <- awaitLeader(cluster.nodes)
        follower = cluster.nodes.find(_.nodeId != leader.nodeId).getOrElse(throw new IllegalStateException("expected follower in three-node demo cluster"))
        followerRead <- cluster.service(follower.nodeId).getLease(GetLeaseRequest(principal, tenantId.value, resourceId.value))
        followerObservedNotLeader = followerRead.left.exists(_.isInstanceOf[ServiceError.NotLeader])
        acquired <- cluster.service(leader.nodeId).acquire(AcquireRequest(principal, tenantId.value, resourceId.value, "holder-a", 15, "demo-acquire"))
        fetched <- cluster.service(leader.nodeId).getLease(GetLeaseRequest(principal, tenantId.value, resourceId.value))
        leaseId <- fetchedLeaseId(fetched)
        renewed <- cluster.service(leader.nodeId).renew(RenewRequest(principal, tenantId.value, resourceId.value, leaseId, "holder-a", 20, "demo-renew"))
        released <- cluster.service(leader.nodeId).release(ReleaseRequest(principal, tenantId.value, resourceId.value, leaseId, "holder-a", "demo-release"))
        firstRetry <- cluster.service(leader.nodeId).acquire(AcquireRequest(principal, tenantId.value, retryResourceId.value, "holder-r", 10, "retry-1"))
        secondRetry <- cluster.service(leader.nodeId).acquire(AcquireRequest(principal, tenantId.value, retryResourceId.value, "holder-r", 10, "retry-1"))
        fencingAcquire1 <- cluster.service(leader.nodeId).acquire(AcquireRequest(principal, tenantId.value, fencingResourceId.value, "holder-old", 10, "fence-1"))
        fencingRead1 <- cluster.service(leader.nodeId).getLease(GetLeaseRequest(principal, tenantId.value, fencingResourceId.value))
        fencingLeaseId1 <- fetchedLeaseId(fencingRead1)
        _ <- cluster.service(leader.nodeId).release(ReleaseRequest(principal, tenantId.value, fencingResourceId.value, fencingLeaseId1, "holder-old", "fence-release")).map(assertRight)
        fencingAcquire2 <- cluster.service(leader.nodeId).acquire(AcquireRequest(principal, tenantId.value, fencingResourceId.value, "holder-new", 10, "fence-2"))
        _ <- cluster.injector.setDelay(FailurePoint.PersistenceAppend, 175)
        delayedAcquire <- cluster.service(leader.nodeId).acquire(AcquireRequest(principal, tenantId.value, delayedResourceId.value, "holder-delay", 30, "delay-1"))
        _ <- cluster.injector.clearDelay(FailurePoint.PersistenceAppend)
        beforeRestartSnapshot <- cluster.observability.snapshot
        failureEventsSeen = beforeRestartSnapshot.counters.exists(sample => sample.metric.name == "failure_injection_total" && sample.value >= 1L)
        _ <- leader.shutdown
        replacementLeader <- awaitLeader(cluster.nodes.filterNot(_.nodeId == leader.nodeId))
        restarted <- allocateNode(cluster.configs(leader.nodeId), cluster.dataDirs(leader.nodeId), cluster.observability, cluster.injector)
        (releaseRestarted, restartedNode) = restarted
        afterRestart <- (
          for
            _ <- awaitFollowerLeaseStatus(restartedNode, tenantId, delayedResourceId, LeaseStatus.Active)
            snapshot <- cluster.observability.snapshot
          yield snapshot
        ).guarantee(releaseRestarted)
        restartRecovered = afterRestart.events.exists(_.eventType == "recovery.completed")
        completedAt <- now
      yield
        DemoReport(
          command = "demo",
          clusterSize = cluster.nodes.size,
          startedAt = startedAt,
          completedAt = completedAt,
          environment = environmentInfo,
          workDir = cluster.root.toString,
          scenarios = List(
            DemoScenario("cluster_bootstrap", success = true, details = Map("leader_node_id" -> leader.nodeId, "peer_count" -> cluster.nodes.size.toString)),
            DemoScenario("lease_lifecycle", success = acquired.isRight && fetched.toOption.exists(_.lease.status == LeaseStatus.Active) && renewed.isRight && released.isRight, details = Map("lease_id" -> leaseId, "get_status" -> fetched.toOption.map(_.lease.status.toString).getOrElse("missing"), "renewed" -> renewed.exists(_.renewed).toString, "released" -> released.exists(_.released).toString)),
            DemoScenario("not_leader_read", success = followerObservedNotLeader, details = Map("follower_node_id" -> follower.nodeId, "result" -> (if followerObservedNotLeader then "NOT_LEADER" else followerRead.fold(_.getClass.getSimpleName, _ => "UNEXPECTED_SUCCESS")))),
            DemoScenario("idempotent_retry", success = firstRetry == secondRetry, details = Map("request_id" -> "retry-1", "same_response" -> (firstRetry == secondRetry).toString)),
            DemoScenario("fencing_turnover", success = fencingAcquire1.toOption.exists(_.lease.fencingToken < fencingAcquire2.toOption.map(_.lease.fencingToken).getOrElse(0L)), details = Map("old_token" -> fencingAcquire1.toOption.map(_.lease.fencingToken).getOrElse(0L).toString, "new_token" -> fencingAcquire2.toOption.map(_.lease.fencingToken).getOrElse(0L).toString)),
            DemoScenario("failure_injection_delay", success = delayedAcquire.isRight && failureEventsSeen, details = Map("failure_point" -> FailurePoint.PersistenceAppend.name, "delay_ms" -> "175", "failure_events_seen" -> failureEventsSeen.toString)),
            DemoScenario("clean_shutdown_restart", success = restartRecovered, details = Map("stopped_node_id" -> leader.nodeId, "replacement_leader_id" -> replacementLeader.nodeId, "restart_recovered" -> restartRecovered.toString))
          ),
          knownLimits = knownLimits
        )
    })

  def runBenchmark(command: BenchmarkCommand = BenchmarkCommand()): IO[BenchmarkReport] =
    prepareRoot(command.workDir).flatMap(root => withCluster(root).use { cluster =>
      val tenantId = TenantId("benchmark-tenant")
      val principal = Principal("benchmark-user", tenantId)

      for
        startedAt <- now
        leader <- awaitLeader(cluster.nodes)
        service = cluster.service(leader.nodeId)
        acquireSamples <- (1 to command.iterations).toList.traverse { idx =>
          timedMillis {
            service.acquire(AcquireRequest(principal, tenantId.value, s"latency-$idx", "holder-a", 10, s"acquire-$idx")).flatMap(assertRightIO)
          }
        }
        renewSamples <- (1 to command.iterations).toList.traverse { idx =>
          for
            _ <- service.acquire(AcquireRequest(principal, tenantId.value, s"renew-$idx", "holder-r", 10, s"renew-acquire-$idx")).flatMap(assertRightIO)
            fetched <- service.getLease(GetLeaseRequest(principal, tenantId.value, s"renew-$idx"))
            leaseId <- fetchedLeaseId(fetched)
            millis <- timedMillis {
              service.renew(RenewRequest(principal, tenantId.value, s"renew-$idx", leaseId, "holder-r", 12, s"renew-$idx")).flatMap(assertRightIO)
            }
          yield millis
        }
        releaseSamples <- (1 to command.iterations).toList.traverse { idx =>
          for
            _ <- service.acquire(AcquireRequest(principal, tenantId.value, s"release-$idx", "holder-x", 10, s"release-acquire-$idx")).flatMap(assertRightIO)
            fetched <- service.getLease(GetLeaseRequest(principal, tenantId.value, s"release-$idx"))
            leaseId <- fetchedLeaseId(fetched)
            millis <- timedMillis {
              service.release(ReleaseRequest(principal, tenantId.value, s"release-$idx", leaseId, "holder-x", s"release-$idx")).flatMap(assertRightIO)
            }
          yield millis
        }
        throughput <- throughputScenario(service, principal, tenantId, command.iterations * command.parallelism, command.parallelism)
        _ <- service.acquire(AcquireRequest(principal, tenantId.value, "failover-resource", "holder-f", 20, "failover-acquire")).flatMap(assertRightIO)
        failoverRead <- service.getLease(GetLeaseRequest(principal, tenantId.value, "failover-resource"))
        failoverLeaseId <- fetchedLeaseId(failoverRead)
        failoverMillis <- timedMillis {
          leader.shutdown *>
            awaitLeader(cluster.nodes.filterNot(_.nodeId == leader.nodeId)).flatMap { replacement =>
              cluster
                .service(replacement.nodeId)
                .renew(RenewRequest(principal, tenantId.value, "failover-resource", failoverLeaseId, "holder-f", 22, "failover-renew"))
                .flatMap(assertRightIO)
            }
        }
        restartMillis <- timedMillis {
          allocateNode(cluster.configs(leader.nodeId), cluster.dataDirs(leader.nodeId), cluster.observability, cluster.injector).flatMap { case (releaseNode, restarted) =>
            awaitFollowerLeaseStatus(restarted, tenantId, ResourceId("failover-resource"), LeaseStatus.Active).guarantee(releaseNode)
          }
        }
        completedAt <- now
      yield
        BenchmarkReport(
          command = "benchmark",
          clusterSize = cluster.nodes.size,
          iterations = command.iterations,
          parallelism = command.parallelism,
          startedAt = startedAt,
          completedAt = completedAt,
          environment = environmentInfo,
          workDir = cluster.root.toString,
          latency = List(
            LatencyMetric("acquire", fromSamples(acquireSamples)),
            LatencyMetric("renew", fromSamples(renewSamples)),
            LatencyMetric("release", fromSamples(releaseSamples))
          ),
          throughput = throughput,
          failover = FailoverMetric("leader_change_renew", failoverMillis),
          recovery = RecoveryMetric("restart_catch_up", restartMillis),
          knownLimits = knownLimits
        )
    })

  private def throughputScenario(
      service: LeaseService[IO],
      principal: Principal,
      tenantId: TenantId,
      operations: Int,
      parallelism: Int
  ): IO[ThroughputMetric] =
    timedMillis {
      (1 to operations).toList
        .grouped(parallelism)
        .toList
        .traverse_ { batch =>
          batch.parTraverse_ { idx =>
            service.acquire(AcquireRequest(principal, tenantId.value, s"throughput-$idx", "holder-t", 10, s"throughput-$idx")).flatMap(assertRightIO)
          }
        }
    }.map { totalMillis =>
      ThroughputMetric("acquire_unique_resources", operations, totalMillis, if totalMillis == 0 then operations.toDouble else (operations.toDouble * 1000d) / totalMillis.toDouble)
    }

  private def withCluster(root: Path): Resource[IO, RunningCluster] =
    for
      observability <- Resource.eval(Observability.inMemory[IO])
      injector <- Resource.eval(FailureInjector.controlled[IO](observability, IO.realTime.map(_.toMillis)))
      reservations <- Resource.make(IO.blocking(reservePeers(3)))(reservations => IO.blocking(reservations.foreach(_.close())))
      peers = reservations.map(_.peer)
      dataDirs = peers.map(peer => peer.nodeId -> root.resolve(peer.nodeId).toString).toMap
      configs = peers.map(peer => peer.nodeId -> ClusterConfig(peer.nodeId, peer.apiHost, peer.apiPort, peers, dataDirs(peer.nodeId))).toMap
      nodes <- reservations.traverse(reservation => nodeResource(configs(reservation.peer.nodeId), dataDirs(reservation.peer.nodeId), observability, injector, Some(reservation)))
    yield
      val services = nodes.map(node => node.nodeId -> LeaseService.replicated[IO](node, Clock.system[IO], observability = observability)).toMap
      RunningCluster(root, nodes, services, configs, dataDirs, observability, injector)

  private def nodeResource(
      config: ClusterConfig,
      dataDir: String,
      observability: InMemoryObservability[IO],
      injector: ControlledFailureInjector[IO],
      reservation: Option[ReservedPeer] = None
  ): Resource[IO, RaftNode[IO]] =
    Resource.eval(reservation.fold(IO.unit)(reserved => IO.blocking(reserved.close()))).flatMap { _ =>
      Resource.eval(RaftPersistence.fileBacked[IO](dataDir, config.nodeId, injector, observability)).flatMap(persistence => RaftNode.resource[IO](config, persistence, observability = observability))
    }

  private def allocateNode(
      config: ClusterConfig,
      dataDir: String,
      observability: InMemoryObservability[IO],
      injector: ControlledFailureInjector[IO]
  ): IO[(IO[Unit], RaftNode[IO])] =
    nodeResource(config, dataDir, observability, injector).allocated.map {
      case (node, release) => (release, node)
    }

  private def awaitLeader(nodes: List[RaftNode[IO]]): IO[RaftNode[IO]] =
    eventually("leader election") {
      nodes.traverse(node => (node.role, node.currentTermValue).mapN((role, _) => node -> role)).map { states =>
        val leaders = states.collect { case (node, NodeRole.Leader) => node }
        leaders match
          case leader :: Nil => leader
          case other         => throw new IllegalStateException(s"expected exactly one leader, found ${other.map(_.nodeId)}")
      }
    }

  private def awaitFollowerLeaseStatus(node: RaftNode[IO], tenantId: TenantId, resourceId: ResourceId, expected: LeaseStatus): IO[Unit] =
    eventually(s"follower view for ${resourceId.value}") {
      IO.realTimeInstant.flatMap { at =>
        node.readState.map { state =>
          val status = state.leaseState.viewAt(com.richrobertson.tenure.model.ResourceKey(tenantId, resourceId), at).status
          if status == expected then ()
          else throw new IllegalStateException(s"expected $expected for ${resourceId.value}, found $status")
        }
      }
    }

  private def eventually[A](label: String, timeout: FiniteDuration = 12.seconds, interval: FiniteDuration = 200.millis)(thunk: IO[A]): IO[A] =
    IO.monotonic.flatMap { started =>
      def loop: IO[A] =
        thunk.handleErrorWith { error =>
          IO.monotonic.flatMap { now =>
            if now - started >= timeout then IO.raiseError(new AssertionError(s"timed out waiting for $label: ${error.getMessage}"))
            else IO.sleep(interval) *> loop
          }
        }
      loop
    }

  private def timedMillis[A](ioa: IO[A]): IO[Long] =
    for
      started <- IO.monotonic
      _ <- ioa
      finished <- IO.monotonic
    yield (finished - started).toMillis

  private def now: IO[String] =
    IO.realTimeInstant.map(_.toString)

  private def assertRight[A](result: Either[ServiceError, A]): Unit =
    result match
      case Right(_)    => ()
      case Left(error) => throw new IllegalStateException(s"expected success, got $error")

  private def assertRightIO[A](result: Either[ServiceError, A]): IO[A] =
    result match
      case Right(value) => IO.pure(value)
      case Left(error)  => IO.raiseError(new IllegalStateException(s"expected success, got $error"))

  private def fetchedLeaseId(result: Either[ServiceError, GetLeaseResult]): IO[String] =
    result match
      case Right(lease) =>
        IO.fromOption(lease.lease.leaseId.map(_.value.toString))(new IllegalStateException("missing lease id"))
      case Left(error) =>
        IO.raiseError(new IllegalStateException(s"expected success, got $error"))

  private def prepareRoot(workDir: Option[Path]): IO[Path] =
    workDir match
      case Some(path) => IO.blocking(Files.createDirectories(path)).as(path)
      case None       => IO.blocking(Files.createTempDirectory("tenure-milestone-8"))

  private def writeOutput(path: Path, json: String): IO[Unit] =
    IO.blocking {
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.writeString(path, json, StandardCharsets.UTF_8)
      ()
    }

  private def reservePeers(size: Int): List[ReservedPeer] =
    (1 to size).toList.map { idx =>
      val raftSocket = new ServerSocket(0)
      val apiSocket = new ServerSocket(0)
      val peer = PeerNode(s"node-$idx", LocalHost, raftSocket.getLocalPort, LocalHost, apiSocket.getLocalPort)
      ReservedPeer(peer, List(raftSocket, apiSocket))
    }

  private def fromSamples(samples: List[Long]): SampleSummary =
    val sorted = samples.sorted
    def percentile(p: Double): Long =
      if sorted.isEmpty then 0L
      else
        val index = math.min(sorted.size - 1, math.floor((sorted.size - 1) * p).toInt)
        sorted(index)

    SampleSummary(
      samples = samples.size,
      minMs = sorted.headOption.getOrElse(0L),
      p50Ms = percentile(0.50),
      p95Ms = percentile(0.95),
      maxMs = sorted.lastOption.getOrElse(0L),
      averageMs = if samples.isEmpty then 0d else samples.sum.toDouble / samples.size.toDouble
    )

  private def environmentInfo: EnvironmentInfo =
    EnvironmentInfo(
      osName = System.getProperty("os.name"),
      osVersion = System.getProperty("os.version"),
      javaVersion = System.getProperty("java.version"),
      availableProcessors = Runtime.getRuntime.availableProcessors()
    )

  private def knownLimits: List[String] =
    List(
      "Numbers are local characterization only and are not production performance claims.",
      "The evaluator uses one shared Raft group because v1 does not benchmark future shard-management behavior.",
      "Failure scenarios stay local-first: static config, local disk, and direct TCP peer endpoints only."
    )

  private[eval] def parseArgs(args: List[String]): Either[String, Command] =
    args match
      case "demo" :: tail      => parseDemo(tail)
      case "benchmark" :: tail => parseBenchmark(tail)
      case _ =>
        Left(
          "usage: sbt \"runMain com.richrobertson.tenure.eval.LocalEvaluation demo [--work-dir <path>] [--output <path>]\"\n" +
            "   or: sbt \"runMain com.richrobertson.tenure.eval.LocalEvaluation benchmark [--iterations <n>] [--parallelism <n>] [--work-dir <path>] [--output <path>]\""
        )

  private def parseDemo(args: List[String]): Either[String, DemoCommand] =
    parseCommon(args).flatMap { case (workDir, output, extras) =>
      if extras.nonEmpty then
        Left(s"unknown option(s) for demo: ${extras.keys.toList.sorted.mkString(", ")}")
      else
        Right(DemoCommand(workDir = workDir, output = output))
    }

  private def parseBenchmark(args: List[String]): Either[String, BenchmarkCommand] =
    parseCommon(args).flatMap { case (workDir, output, extras) =>
      for
        iterations <- parsePositiveInt(extras.get("iterations"), "iterations", default = 20)
        parallelism <- parsePositiveInt(extras.get("parallelism"), "parallelism", default = 4)
      yield BenchmarkCommand(iterations = iterations, parallelism = parallelism, workDir = workDir, output = output)
    }

  private def parseCommon(args: List[String]): Either[String, (Option[Path], Option[Path], Map[String, String])] =
    def loop(remaining: List[String], workDir: Option[Path], output: Option[Path], extras: Map[String, String]): Either[String, (Option[Path], Option[Path], Map[String, String])] =
      remaining match
        case Nil => Right((workDir, output, extras))
        case "--work-dir" :: Nil => Left("--work-dir requires a value")
        case "--output" :: Nil => Left("--output requires a value")
        case "--iterations" :: Nil => Left("--iterations requires a value")
        case "--parallelism" :: Nil => Left("--parallelism requires a value")
        case "--work-dir" :: value :: tail =>
          parsePathOption(value, "--work-dir").flatMap(path => loop(tail, Some(path), output, extras))
        case "--output" :: value :: tail =>
          parsePathOption(value, "--output").flatMap(path => loop(tail, workDir, Some(path), extras))
        case "--iterations" :: value :: tail => loop(tail, workDir, output, extras.updated("iterations", value))
        case "--parallelism" :: value :: tail => loop(tail, workDir, output, extras.updated("parallelism", value))
        case option :: _ => Left(s"unknown option: $option")

    loop(args, None, None, Map.empty)

  private def parsePathOption(raw: String, flag: String): Either[String, Path] =
    if raw.contains('\u0000') then
      Left(s"invalid $flag path: '$raw'")
    else
      Either
        .catchNonFatal(Path.of(raw))
        .leftMap(_ => s"invalid $flag path: '$raw'")

  private def parsePositiveInt(raw: Option[String], label: String, default: Int): Either[String, Int] =
    raw match
      case None => Right(default)
      case Some(value) =>
        Either
          .catchNonFatal(value.toInt)
          .leftMap(_ => s"$label must be a positive integer, found '$value'")
          .flatMap(parsed => Either.cond(parsed > 0, parsed, s"$label must be a positive integer, found '$value'"))

  sealed trait Command:
    def output: Option[Path]

  final case class DemoCommand(workDir: Option[Path] = None, output: Option[Path] = None) extends Command
  final case class BenchmarkCommand(iterations: Int = 20, parallelism: Int = 4, workDir: Option[Path] = None, output: Option[Path] = None) extends Command
  final case class EvaluationResult(json: String, success: Boolean)

  final case class RunningCluster(
      root: Path,
      nodes: List[RaftNode[IO]],
      services: Map[String, LeaseService[IO]],
      configs: Map[String, ClusterConfig],
      dataDirs: Map[String, String],
      observability: InMemoryObservability[IO],
      injector: ControlledFailureInjector[IO]
  ):
    def service(nodeId: String): LeaseService[IO] = services(nodeId)

  final case class ReservedPeer(peer: PeerNode, sockets: List[ServerSocket]):
    def close(): Unit =
      sockets.foreach(socket => if !socket.isClosed then socket.close())

  final case class EnvironmentInfo(osName: String, osVersion: String, javaVersion: String, availableProcessors: Int)
  final case class DemoScenario(name: String, success: Boolean, details: Map[String, String])
  final case class DemoReport(command: String, clusterSize: Int, startedAt: String, completedAt: String, environment: EnvironmentInfo, workDir: String, scenarios: List[DemoScenario], knownLimits: List[String])
  final case class SampleSummary(samples: Int, minMs: Long, p50Ms: Long, p95Ms: Long, maxMs: Long, averageMs: Double)
  final case class LatencyMetric(name: String, summary: SampleSummary)
  final case class ThroughputMetric(name: String, operations: Int, totalMillis: Long, opsPerSecond: Double)
  final case class FailoverMetric(name: String, millisToSuccessfulRenew: Long)
  final case class RecoveryMetric(name: String, millisToRecoveredFollowerView: Long)
  final case class BenchmarkReport(command: String, clusterSize: Int, iterations: Int, parallelism: Int, startedAt: String, completedAt: String, environment: EnvironmentInfo, workDir: String, latency: List[LatencyMetric], throughput: ThroughputMetric, failover: FailoverMetric, recovery: RecoveryMetric, knownLimits: List[String])

  given Encoder[EnvironmentInfo] = deriveEncoder
  given Encoder[DemoScenario] = deriveEncoder
  given Encoder[DemoReport] = deriveEncoder
  given Encoder[SampleSummary] = deriveEncoder
  given Encoder[LatencyMetric] = deriveEncoder
  given Encoder[ThroughputMetric] = deriveEncoder
  given Encoder[FailoverMetric] = deriveEncoder
  given Encoder[RecoveryMetric] = deriveEncoder
  given Encoder[BenchmarkReport] = deriveEncoder
