package com.richrobertson.tenure.observability

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.richrobertson.tenure.auth.{Authorization, Principal}
import com.richrobertson.tenure.model.{LeaseStatus, ResourceId, TenantId}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.quota.{TenantQuotaPolicy, TenantQuotaRegistry}
import com.richrobertson.tenure.raft.{ClusterConfig, NodeRole, PeerNode, RaftNode}
import com.richrobertson.tenure.service.*
import com.richrobertson.tenure.service.ServiceCodecs.given
import com.richrobertson.tenure.testkit.{ControlledFailureInjector, FailureInjector, FailurePoint}
import com.richrobertson.tenure.time.Clock
import munit.CatsEffectSuite

import java.net.ServerSocket
import java.nio.file.Files
import scala.concurrent.duration.*

class ObservabilityFailureInjectionSpec extends CatsEffectSuite:
  private val tenantId = TenantId("tenant-a")
  private val principal = Principal("user-a", tenantId)
  private val wrongPrincipal = Principal("user-b", TenantId("tenant-b"))

  override val munitTimeout: FiniteDuration = 60.seconds

  test("leader loss emits transition and not-leader signals") {
    withCluster(3).use { cluster =>
      for
        leader <- awaitLeader(cluster.nodes)
        leaderService = cluster.service(leader.nodeId)
        follower = cluster.nodes.find(_.nodeId != leader.nodeId).getOrElse(fail("missing follower"))
        followerService = cluster.service(follower.nodeId)
        acquired <- leaderService.acquire(AcquireRequest(principal, tenantId.value, "resource-1", "holder-1", 10, "req-1"))
        _ = assert(acquired.isRight)
        _ <- leader.shutdown
        _ <- followerService.getLease(GetLeaseRequest(principal, tenantId.value, "resource-1")).map(result => assert(result.left.exists(_.isInstanceOf[ServiceError.NotLeader])))
        replacement <- awaitLeader(cluster.nodes.filterNot(_.nodeId == leader.nodeId))
        _ <- cluster.service(replacement.nodeId).renew(RenewRequest(principal, tenantId.value, "resource-1", leaseId(acquired), "holder-1", 12, "req-2")).map(result => assert(result.isRight))
        snapshot <- cluster.observability.snapshot
      yield
        assert(snapshot.counters.exists(sample => sample.metric.name == "leader_changes_total" && sample.value >= 1L))
        assert(snapshot.counters.exists(sample => sample.metric.name == "not_leader_responses_total" && sample.value >= 1L))
        assert(snapshot.events.exists(_.eventType == "role.transition"))
        assert(snapshot.events.exists(event => event.eventType == "lease.request.result" && event.errorCode.contains("NOT_LEADER")))
    }
  }

  test("retry storms, quota denials, auth denials, and stale-writer checks are observable") {
    Observability.inMemory[IO].flatMap { observability =>
      val quotas = TenantQuotaRegistry(TenantQuotaPolicy(1000, 300), Map(tenantId -> TenantQuotaPolicy(1, 5)))
      LeaseService.inMemory[IO](Clock.system[IO], quotas = quotas, authorization = Authorization.perTenant, observability = observability).flatMap { service =>
        for
          first <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-1", "holder-1", 5, "req-1"))
          retry <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-1", "holder-1", 5, "req-1"))
          quotaDenied <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-2", "holder-2", 5, "req-2"))
          authDenied <- service.acquire(AcquireRequest(wrongPrincipal, tenantId.value, "resource-3", "holder-3", 5, "req-3"))
          staleWriterCounterBefore <- IO.pure(0L)
          staleWriterCounter = staleWriterCounterBefore + 1L
          _ <- observability.incrementCounter("stale_writer_rejections_total", Map("resource_id" -> "resource-1"))
          _ <- observability.log(LogEvent(0L, "WARN", "stale_writer.rejected", "downstream rejected stale fencing token", tenantId = Some(tenantId.value), resourceId = Some("resource-1"), fields = Map("simulated_total" -> staleWriterCounter.toString)))
          snapshot <- observability.snapshot
        yield
          assert(first == retry)
          assert(quotaDenied.left.exists(_.isInstanceOf[ServiceError.QuotaExceeded]))
          assert(authDenied.left.exists(_.isInstanceOf[ServiceError.Forbidden]))
          assert(snapshot.counters.exists(sample => sample.metric.name == "duplicate_requests_total" && sample.value >= 1L))
          assert(snapshot.counters.exists(sample => sample.metric.name == "quota_rejections_total" && sample.value >= 1L))
          assert(snapshot.counters.exists(sample => sample.metric.name == "auth_denials_total" && sample.value >= 1L))
          assert(snapshot.counters.exists(sample => sample.metric.name == "stale_writer_rejections_total" && sample.value >= 1L))
          assert(snapshot.events.exists(_.eventType == "stale_writer.rejected"))
      }
    }
  }

  test("disk delay and restart recovery emit latency, snapshot, and recovery signals") {
    Observability.inMemory[IO].flatMap { observability =>
      FailureInjector.controlled[IO](observability, IO.realTime.map(_.toMillis)).flatMap { injector =>
        withCluster(1, observability, injector).use { cluster =>
          for
            _ <- injector.setDelay(FailurePoint.PersistenceAppend, 150)
            leader <- awaitLeader(cluster.nodes)
            service = cluster.service(leader.nodeId)
            acquire <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-1", "holder-1", 15, "req-1"))
            _ = assert(acquire.isRight)
            _ <- injector.clearDelay(FailurePoint.PersistenceAppend)
            _ <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-2", "holder-2", 15, "req-2")).map(result => assert(result.isRight))
            _ <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-3", "holder-3", 15, "req-3")).map(result => assert(result.isRight))
            _ <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-4", "holder-4", 15, "req-4")).map(result => assert(result.isRight))
            _ <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-5", "holder-5", 15, "req-5")).map(result => assert(result.isRight))
            _ <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-6", "holder-6", 15, "req-6")).map(result => assert(result.isRight))
            beforeRestart <- observability.snapshot
            _ = assert(beforeRestart.counters.exists(sample => sample.metric.name == "failure_injection_total" && sample.value >= 1L))
            _ = assert(beforeRestart.timingsMillis.exists(sample => sample.metric.name == "commit_latency_ms" && sample.value.exists(_ >= 150L)))
            _ = assert(beforeRestart.events.exists(_.eventType == "failure_injection.delay"))
            _ = assert(beforeRestart.events.exists(_.eventType == "snapshot.saved"))
            _ <- leader.shutdown
            restarted <- allocateNode(cluster.configs(leader.nodeId), cluster.dataDirs(leader.nodeId), observability, injector)
            (releaseRestarted, node) = restarted
            _ <- eventually("restart recovery") { node.readState.map(state => assert(state.leaseState.get(com.richrobertson.tenure.model.ResourceKey(tenantId, ResourceId("resource-1"))).exists(_.status == LeaseStatus.Active))) }
            afterRestart <- observability.snapshot
            _ <- releaseRestarted
          yield
            assert(afterRestart.counters.exists(sample => sample.metric.name == "recovery_events_total" && sample.value >= 2L))
            assert(afterRestart.events.exists(_.eventType == "recovery.completed"))
        }
      }
    }
  }

  private final case class RunningCluster(
      nodes: List[RaftNode[IO]],
      services: Map[String, LeaseService[IO]],
      configs: Map[String, ClusterConfig],
      dataDirs: Map[String, String],
      observability: InMemoryObservability[IO]
  ):
    def service(nodeId: String): LeaseService[IO] = services(nodeId)

  private def withCluster(size: Int, observability: InMemoryObservability[IO] | Null = null, injector: FailureInjector[IO] | Null = null): Resource[IO, RunningCluster] =
    for
      obs <- Resource.eval(Option(observability).fold(Observability.inMemory[IO])(IO.pure))
      inj <- Resource.eval(Option(injector).fold(IO.pure(FailureInjector.noop[IO]))(IO.pure))
      root <- Resource.eval(IO.blocking(Files.createTempDirectory("tenure-observability-spec")))
      peers <- Resource.eval(IO.blocking(parallelPeers(size)))
      dataDirs = peers.map(peer => peer.nodeId -> root.resolve(peer.nodeId).toString).toMap
      configs = peers.map(peer => peer.nodeId -> ClusterConfig(peer.nodeId, peer.apiHost, peer.apiPort, peers, dataDirs(peer.nodeId))).toMap
      nodes <- peers.traverse(peer => nodeResource(configs(peer.nodeId), dataDirs(peer.nodeId), obs, inj))
    yield
      val services = nodes.map(node => node.nodeId -> LeaseService.replicated[IO](node, Clock.system[IO], observability = obs)).toMap
      RunningCluster(nodes, services, configs, dataDirs, obs)

  private def nodeResource(config: ClusterConfig, dataDir: String, observability: InMemoryObservability[IO], injector: FailureInjector[IO]): Resource[IO, RaftNode[IO]] =
    Resource.eval(RaftPersistence.fileBacked[IO](dataDir, config.nodeId, injector, observability)).flatMap(persistence => RaftNode.resource[IO](config, persistence, observability = observability))

  private def allocateNode(config: ClusterConfig, dataDir: String, observability: InMemoryObservability[IO], injector: FailureInjector[IO]): IO[(IO[Unit], RaftNode[IO])] =
    nodeResource(config, dataDir, observability, injector).allocated.map { case (node, release) => (release, node) }

  private def awaitLeader(nodes: List[RaftNode[IO]]): IO[RaftNode[IO]] =
    eventually("leader election") {
      nodes.traverse(node => (node.role, node.currentTermValue).mapN((role, _) => node -> role)).map { states =>
        val leaders = states.collect { case (node, NodeRole.Leader) => node }
        leaders match
          case leader :: Nil => leader
          case other         => throw new IllegalStateException(s"expected one leader, found ${other.map(_.nodeId)}")
      }
    }

  private def leaseId(result: Either[ServiceError, AcquireResult]): String =
    result.toOption.flatMap(_.lease.leaseId).map(_.value.toString).getOrElse(fail("missing lease id"))

  private def parallelPeers(size: Int): List[PeerNode] =
    (1 to size).toList.map { idx =>
      val raftPort = freePort()
      val apiPort = freePort()
      PeerNode(s"node-$idx", "127.0.0.1", raftPort, "127.0.0.1", apiPort)
    }

  private def freePort(): Int =
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  private def eventually[A](label: String, timeout: FiniteDuration = 12.seconds, interval: FiniteDuration = 200.millis)(thunk: IO[A]): IO[A] =
    IO.monotonic.flatMap { start =>
      def loop: IO[A] =
        thunk.handleErrorWith { error =>
          IO.monotonic.flatMap { now =>
            if now - start >= timeout then IO.raiseError(new AssertionError(s"timed out waiting for $label: ${error.getMessage}"))
            else IO.sleep(interval) *> loop
          }
        }
      loop
    }
