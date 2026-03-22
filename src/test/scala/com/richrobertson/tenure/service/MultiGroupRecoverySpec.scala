package com.richrobertson.tenure.service

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.richrobertson.tenure.auth.Principal
import com.richrobertson.tenure.group.{GroupId, GroupRuntime}
import com.richrobertson.tenure.model.{ResourceId, TenantId}
import com.richrobertson.tenure.observability.Observability
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, NodeRole, PeerNode, RaftNode}
import com.richrobertson.tenure.routing.HashRouter
import com.richrobertson.tenure.service.ServiceCodecs.given
import com.richrobertson.tenure.time.Clock
import munit.CatsEffectSuite

import java.net.ServerSocket
import java.nio.file.Files
import scala.concurrent.duration.*

class MultiGroupRecoverySpec extends CatsEffectSuite:
  private val tenantId = TenantId("tenant-a")
  private val principal = Principal("recover", tenantId)
  private val groupIds = Vector(GroupId("group-1"), GroupId("group-2"))

  override val munitTimeout: FiniteDuration = 60.seconds

  test("multi-group local simulation recovers each routed group independently") {
    Observability.inMemory[IO].flatMap { observability =>
      IO.blocking(Files.createTempDirectory("tenure-multi-group-recovery")).flatMap { root =>
        val router = HashRouter(groupIds)
        val resourceA = resourceForGroup(router, GroupId("group-1"), "recover-a")
        val resourceB = resourceForGroup(router, GroupId("group-2"), "recover-b")

        withRoutedReplicatedService(root, observability).use { running =>
          for
            _ <- running.nodes.traverse_(awaitLeader)
            acquiredA <- running.service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 20, "req-a"))
            acquiredB <- running.service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 20, "req-b"))
            _ = assert(acquiredA.isRight)
            _ = assert(acquiredB.isRight)
          yield ()
        } *> observability.snapshot.flatMap { baseline =>
          withRoutedReplicatedService(root, observability).use { restarted =>
            for
              _ <- restarted.nodes.traverse_(awaitLeader)
              leaseA <- restarted.service.getLease(GetLeaseRequest(principal, tenantId.value, resourceA.value))
              leaseB <- restarted.service.getLease(GetLeaseRequest(principal, tenantId.value, resourceB.value))
              snapshot <- observability.snapshot
            yield
              assertEquals(leaseA.toOption.map(_.lease.holderId.map(_.value)), Some(Some("holder-a")))
              assertEquals(leaseB.toOption.map(_.lease.holderId.map(_.value)), Some(Some("holder-b")))
              assert(recoveryCount(snapshot, GroupId("group-1")) > recoveryCount(baseline, GroupId("group-1")))
              assert(recoveryCount(snapshot, GroupId("group-2")) > recoveryCount(baseline, GroupId("group-2")))
          }
        }
      }
    }
  }

  private final case class RunningService(service: LeaseService[IO], nodes: List[RaftNode[IO]])

  private def withRoutedReplicatedService(root: java.nio.file.Path, observability: com.richrobertson.tenure.observability.InMemoryObservability[IO]): Resource[IO, RunningService] =
    groupIds.toList
      .traverse(groupId => singleNodeGroup(root.resolve(groupId.value).toString, groupId, observability))
      .evalMap { groups =>
        val router = HashRouter(groupIds)
        LeaseService.routed[IO](router, groups.map(_.runtime), Clock.system[IO], observability).map(service => RunningService(service, groups.map(_.node)))
      }

  private final case class RunningGroup(runtime: GroupRuntime[IO], node: RaftNode[IO])

  private def singleNodeGroup(dataDir: String, groupId: GroupId, observability: com.richrobertson.tenure.observability.InMemoryObservability[IO]): Resource[IO, RunningGroup] =
    for
      peer <- Resource.eval(IO.blocking(singlePeer(groupId)))
      config = ClusterConfig(peer.nodeId, peer.apiHost, peer.apiPort, List(peer), dataDir)
      groupObservability = Observability.scoped(observability, Map("group_id" -> groupId.value), Map("group_id" -> groupId.value))
      persistence <- Resource.eval(RaftPersistence.fileBacked[IO](dataDir, config.nodeId, groupObservability))
      node <- RaftNode.resource[IO](config, persistence, observability = groupObservability)
    yield RunningGroup(GroupRuntime.replicated[IO](groupId, node, Clock.system[IO], groupObservability), node)

  private def awaitLeader(node: RaftNode[IO]): IO[Unit] =
    eventually("single-node leader election") {
      node.role.map {
        case NodeRole.Leader => ()
        case other           => throw new IllegalStateException(s"expected leader, found $other")
      }
    }

  private def resourceForGroup(router: HashRouter, groupId: GroupId, prefix: String): ResourceId =
    (1 to 512)
      .iterator
      .map(idx => ResourceId(s"$prefix-$idx"))
      .find(resourceId => router.route(tenantId, resourceId).groupId == groupId)
      .getOrElse(fail(s"expected to find resource for ${groupId.value}"))

  private def singlePeer(groupId: GroupId): PeerNode =
    val raftPort = freePort()
    val apiPort = freePort()
    PeerNode(s"node-${groupId.value}", "127.0.0.1", raftPort, "127.0.0.1", apiPort)

  private def freePort(): Int =
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  private def recoveryCount(snapshot: com.richrobertson.tenure.observability.ObservabilitySnapshot, groupId: GroupId): Long =
    snapshot.counters
      .collectFirst {
        case sample if sample.metric.name == "recovery_events_total" && sample.metric.labels.get("group_id").contains(groupId.value) => sample.value
      }
      .getOrElse(0L)

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
