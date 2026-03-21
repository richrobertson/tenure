package com.richrobertson.tenure.service

import cats.effect.kernel.Resource
import cats.effect.IO
import cats.syntax.all.*
import com.richrobertson.tenure.model.{LeaseStatus, ResourceId, TenantId}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, NodeRole, PeerNode, PersistedMetadata, RaftLogEntry, RaftNode}
import com.richrobertson.tenure.time.Clock
import munit.CatsEffectSuite

import java.net.ServerSocket
import java.nio.file.Files
import java.time.Instant
import scala.concurrent.duration.*
import java.util.UUID

class RaftIntegrationSpec extends CatsEffectSuite:
  private val appliedAt = Instant.parse("2026-03-21T12:00:00Z")
  private val tenantId = TenantId("tenant-a")
  private val resourceId = ResourceId("resource-1")

  test("multi-node cluster preserves leader-only reads, failover authority, and follower catch-up after restart") {
    withCluster(3).use { cluster =>
      for
        initialLeader <- awaitLeader(cluster.nodes)
        initialLeaderService = cluster.service(initialLeader.nodeId)
        initialFollower = cluster.nodes.find(_.nodeId != initialLeader.nodeId).getOrElse(fail("expected a follower in a three-node cluster"))
        followerService = cluster.service(initialFollower.nodeId)
        followerRead <- followerService.getLease(tenantId, resourceId)
        _ = assert(followerRead.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
        acquire <- initialLeaderService.acquire(AcquireRequest(tenantId.value, resourceId.value, "holder-1", 15, "req-1"))
        _ = assert(acquire.isRight)
        _ <- awaitLeaseStatus(cluster.nodes, LeaseStatus.Active)
        _ <- initialLeader.shutdown
        replacementLeader <- awaitLeader(cluster.nodes.filterNot(_.nodeId == initialLeader.nodeId))
        _ = assertNotEquals(replacementLeader.nodeId, initialLeader.nodeId)
        replacementService = cluster.service(replacementLeader.nodeId)
        afterFailover <- replacementService.getLease(tenantId, resourceId)
        _ = assertEquals(afterFailover.toOption.map(_.lease.status), Some(LeaseStatus.Active))
        restarted <- allocateNode(cluster.configs(initialLeader.nodeId), cluster.dataDirs(initialLeader.nodeId))
        (releaseRestarted, restartedNode) = restarted
        _ <- awaitFollowerView(restartedNode, LeaseStatus.Active)
        release <- replacementService.release(ReleaseRequest(tenantId.value, resourceId.value, leaseId(acquire), "holder-1", "req-2"))
        _ = assert(release.isRight)
        _ <- awaitLeaseStatus(cluster.nodes.filterNot(_.nodeId == initialLeader.nodeId) :+ restartedNode, LeaseStatus.Released)
        _ <- releaseRestarted
      yield ()
    }
  }

  test("persisted committed log reload reconstructs the authoritative materialized lease view") {
    val resourceKey = com.richrobertson.tenure.model.ResourceKey(tenantId, resourceId)
    val leaseId = com.richrobertson.tenure.model.LeaseId(UUID.fromString("00000000-0000-0000-0000-000000000321"))
    val acquire = AcquireCommand(
      RequestContext(tenantId, com.richrobertson.tenure.model.RequestId("req-1"), resourceKey),
      com.richrobertson.tenure.model.ClientId("holder-1"),
      ttlSeconds = 15,
      leaseId = leaseId,
      appliedAt = appliedAt
    )
    val renew = RenewCommand(
      RequestContext(tenantId, com.richrobertson.tenure.model.RequestId("req-2"), resourceKey),
      leaseId = leaseId,
      holderId = com.richrobertson.tenure.model.ClientId("holder-1"),
      ttlSeconds = 30,
      appliedAt = appliedAt.plusSeconds(5)
    )

    for
      root <- IO.blocking(Files.createTempDirectory("tenure-raft-replay-spec"))
      persistence <- RaftPersistence.fileBacked[IO](root.toString)
      _ <- persistence.appendEntry(RaftLogEntry(1L, 3L, acquire))
      _ <- persistence.appendEntry(RaftLogEntry(2L, 3L, renew))
      _ <- persistence.saveMetadata(PersistedMetadata(currentTerm = 3L, votedFor = Some("node-1"), commitIndex = 2L))
      loaded <- persistence.load
      replayed = loaded.entries.filter(_.index <= loaded.metadata.commitIndex).sortBy(_.index).foldLeft(ServiceState.empty) { case (state, entry) =>
        LeaseMaterializer.applyCommand(state, entry.command)
      }
      leaseView = replayed.leaseState.viewAt(resourceKey, appliedAt.plusSeconds(10))
    yield
      assertEquals(loaded.metadata.commitIndex, 2L)
      assertEquals(leaseView.status, LeaseStatus.Active)
      assertEquals(leaseView.leaseId, Some(leaseId))
      assertEquals(leaseView.holderId.map(_.value), Some("holder-1"))
      assertEquals(leaseView.expiresAt, Some(appliedAt.plusSeconds(35)))
      assert(replayed.responses.contains((tenantId, com.richrobertson.tenure.model.RequestId("req-1"))))
      assert(replayed.responses.contains((tenantId, com.richrobertson.tenure.model.RequestId("req-2"))))
  }

  private final case class RunningCluster(
      nodes: List[RaftNode[IO]],
      services: Map[String, LeaseService[IO]],
      configs: Map[String, ClusterConfig],
      dataDirs: Map[String, String]
  ):
    def service(nodeId: String): LeaseService[IO] =
      services.getOrElse(nodeId, fail(s"missing service for $nodeId"))

  private def withCluster(size: Int): Resource[IO, RunningCluster] =
    for
      root <- Resource.eval(IO.blocking(Files.createTempDirectory("tenure-raft-cluster-spec")))
      peers <- Resource.eval(IO(parallelPeers(size)))
      dataDirs = peers.map(peer => peer.nodeId -> root.resolve(peer.nodeId).toString).toMap
      configs = peers.map(peer => peer.nodeId -> ClusterConfig(peer.nodeId, peer.apiHost, peer.apiPort, peers, dataDirs(peer.nodeId))).toMap
      nodes <- peers.traverse(peer => nodeResource(configs(peer.nodeId), dataDirs(peer.nodeId)))
    yield
      val services = nodes.map(node => node.nodeId -> LeaseService.replicated[IO](node, Clock.system[IO])).toMap
      RunningCluster(nodes, services, configs, dataDirs)

  private def nodeResource(config: ClusterConfig, dataDir: String): Resource[IO, RaftNode[IO]] =
    Resource.eval(RaftPersistence.fileBacked[IO](dataDir)).flatMap(persistence => RaftNode.resource[IO](config, persistence))

  private def allocateNode(config: ClusterConfig, dataDir: String): IO[(IO[Unit], RaftNode[IO])] =
    nodeResource(config, dataDir).allocated.map { case (node, release) => (release, node) }

  private def awaitLeader(nodes: List[RaftNode[IO]]): IO[RaftNode[IO]] =
    eventually("leader election") {
      nodes.traverse(node => node.role.map(role => node -> role)).map { states =>
        val leaders = states.collect { case (node, NodeRole.Leader) => node }
        leaders match
          case leader :: Nil => leader
          case other         => throw new IllegalStateException(s"expected exactly one leader, found ${other.map(_.nodeId)}")
      }
    }

  private def awaitLeaseStatus(nodes: List[RaftNode[IO]], expected: LeaseStatus): IO[Unit] =
    eventually(s"lease status $expected across cluster") {
      nodes.traverse(_.readState).map { states =>
        val statuses = states.map(_.leaseState.viewAt(com.richrobertson.tenure.model.ResourceKey(tenantId, resourceId), appliedAt.plusSeconds(10)).status)
        if statuses.forall(_ == expected) then ()
        else throw new IllegalStateException(s"expected all statuses to be $expected, found $statuses")
      }
    }

  private def awaitFollowerView(node: RaftNode[IO], expected: LeaseStatus): IO[Unit] =
    eventually(s"restarted follower catch-up to $expected") {
      node.readState.map(_.leaseState.viewAt(com.richrobertson.tenure.model.ResourceKey(tenantId, resourceId), appliedAt.plusSeconds(10)).status).flatMap { status =>
        if status == expected then IO.unit
        else IO.raiseError(new IllegalStateException(s"expected restarted follower status $expected, found $status"))
      }
    }

  private def eventually[A](label: String, timeout: FiniteDuration = 8.seconds, interval: FiniteDuration = 200.millis)(thunk: IO[A]): IO[A] =
    IO.monotonic.flatMap { start =>
      def loop(lastError: Option[Throwable]): IO[A] =
        thunk.handleErrorWith { error =>
          IO.monotonic.flatMap { now =>
            if now - start >= timeout then IO.raiseError(lastError.getOrElse(error))
            else IO.sleep(interval) *> loop(Some(error))
          }
        }
      loop(None)
    }.adaptError { case error => AssertionError(s"timed out waiting for $label: ${error.getMessage}") }

  private def leaseId(result: Either[ServiceError, AcquireResult]): String =
    result match
      case Right(value) => value.lease.leaseId.map(_.value.toString).getOrElse(fail("expected lease id in acquire result"))
      case Left(error)  => fail(s"expected successful acquire, got $error")

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
