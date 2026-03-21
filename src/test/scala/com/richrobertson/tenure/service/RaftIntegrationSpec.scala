package com.richrobertson.tenure.service

import cats.effect.kernel.Resource
import cats.effect.IO
import cats.syntax.all.*
import com.richrobertson.tenure.auth.Principal
import com.richrobertson.tenure.model.{LeaseStatus, ResourceId, TenantId}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.quota.TenantQuotaPolicy
import com.richrobertson.tenure.raft.{ClusterConfig, NodeRole, PeerNode, PersistedMetadata, PersistedSnapshot, RaftLogEntry, RaftNode}
import com.richrobertson.tenure.service.ServiceCodecs.given
import com.richrobertson.tenure.time.Clock
import munit.CatsEffectSuite

import java.net.ServerSocket
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import java.util.UUID

class RaftIntegrationSpec extends CatsEffectSuite:
  private val appliedAt = Instant.parse("2026-03-21T12:00:00Z")
  private val tenantId = TenantId("tenant-a")
  private val resourceId = ResourceId("resource-1")
  private val principal = Principal("user-a", tenantId)

  override val munitTimeout: FiniteDuration = 60.seconds

  test("multi-node cluster preserves leader-only reads, failover authority, and follower catch-up after restart") {
    retrying("three-node failover cluster", attempts = 3) {
      withCluster(3).use { cluster =>
        for
          initialLeader <- awaitLeader(cluster.nodes)
          initialLeaderService = cluster.service(initialLeader.nodeId)
          initialFollower = cluster.nodes.find(_.nodeId != initialLeader.nodeId).getOrElse(fail("expected a follower in a three-node cluster"))
          followerService = cluster.service(initialFollower.nodeId)
          followerRead <- followerService.getLease(GetLeaseRequest(principal, tenantId.value, resourceId.value))
          _ = assert(followerRead.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
          acquire <- initialLeaderService.acquire(AcquireRequest(principal, tenantId.value, resourceId.value, "holder-1", 15, "req-1"))
          _ = assert(acquire.isRight)
          _ <- awaitLeaseStatus(cluster.nodes, LeaseStatus.Active)
          _ <- initialLeader.shutdown
          replacementLeader <- awaitLeader(cluster.nodes.filterNot(_.nodeId == initialLeader.nodeId))
          _ = assertNotEquals(replacementLeader.nodeId, initialLeader.nodeId)
          replacementService = cluster.service(replacementLeader.nodeId)
          afterFailover <- replacementService.getLease(GetLeaseRequest(principal, tenantId.value, resourceId.value))
          _ = assertEquals(afterFailover.toOption.map(_.lease.status), Some(LeaseStatus.Active))
          restarted <- allocateNode(cluster.configs(initialLeader.nodeId), cluster.dataDirs(initialLeader.nodeId))
          (releaseRestarted, restartedNode) = restarted
          _ <- awaitFollowerView(restartedNode, LeaseStatus.Active)
          release <- replacementService.release(ReleaseRequest(principal, tenantId.value, resourceId.value, leaseId(acquire), "holder-1", "req-2"))
          _ = assert(release.isRight)
          _ <- awaitLeaseStatus(cluster.nodes.filterNot(_.nodeId == initialLeader.nodeId) :+ restartedNode, LeaseStatus.Released)
          _ <- releaseRestarted
        yield ()
      }
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
      quotaPolicy = TenantQuotaPolicy(maxActiveLeases = 1000, maxTtlSeconds = 15),
      appliedAt = appliedAt
    )
    val renew = RenewCommand(
      RequestContext(tenantId, com.richrobertson.tenure.model.RequestId("req-2"), resourceKey),
      leaseId = leaseId,
      holderId = com.richrobertson.tenure.model.ClientId("holder-1"),
      ttlSeconds = 30,
      quotaPolicy = TenantQuotaPolicy(maxActiveLeases = 1000, maxTtlSeconds = 30),
      appliedAt = appliedAt.plusSeconds(5)
    )

    for
      root <- IO.blocking(Files.createTempDirectory("tenure-raft-replay-spec"))
      persistence <- RaftPersistence.fileBacked[IO](root.toString)
      _ <- persistence.appendEntry(RaftLogEntry(1L, 3L, acquire))
      _ <- persistence.appendEntry(RaftLogEntry(2L, 3L, renew))
      _ <- persistence.saveMetadata(PersistedMetadata(currentTerm = 3L, votedFor = Some("node-1"), commitIndex = 2L, lastApplied = 2L))
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

  test("snapshot recovery and compaction preserve leases, dedupe, fencing, and tenant quota state") {
    retrying("snapshot recovery", attempts = 2, attemptTimeout = 25.seconds) {
      withCluster(3).use { cluster =>
        for
          leader <- awaitLeader(cluster.nodes)
          service = cluster.service(leader.nodeId)
          acquire1 <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-1", "holder-1", 20, "req-1"))
          _ = assert(acquire1.isRight)
          acquire2 <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-2", "holder-2", 20, "req-2"))
          _ = assert(acquire2.isRight)
          leaseId1 = leaseId(acquire1)
          leaseId2 = leaseId(acquire2)
          _ <- service.renew(RenewRequest(principal, tenantId.value, "resource-1", leaseId1, "holder-1", 25, "req-3")).map(result => assert(result.isRight))
          _ <- service.release(ReleaseRequest(principal, tenantId.value, "resource-2", leaseId2, "holder-2", "req-4")).map(result => assert(result.isRight))
          _ <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-3", "holder-3", 20, "req-5")).map(result => assert(result.isRight))
          _ <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-4", "holder-4", 20, "req-6")).map(result => assert(result.isRight))
          _ <- eventually("snapshot file creation and decodability") {
            IO.blocking {
              val snapshotPath = Paths.get(cluster.dataDirs(leader.nodeId)).resolve("snapshot.json")
              val snapshotJson = Files.readString(snapshotPath)
              if snapshotJson.trim.isEmpty then
                throw new IllegalStateException("snapshot file exists but is empty")
              // Ensure snapshot is decodable; any failure will cause eventually to retry.
              io.circe.parser.decode[PersistedSnapshot](snapshotJson).fold(throw _, _ => ())
            }
          }
          restart <- allocateNode(cluster.configs(leader.nodeId), cluster.dataDirs(leader.nodeId))
          (releaseRestarted, restartedNode) = restart
          _ <- awaitFollowerView(restartedNode, LeaseStatus.Active)
          replayedRead <- restartedNode.readState
          _ = assert(replayedRead.responses.contains((tenantId, com.richrobertson.tenure.model.RequestId("req-1"))))
          snapshotJson <- IO.blocking(Files.readString(Paths.get(cluster.dataDirs(leader.nodeId)).resolve("snapshot.json")))
          snapshot = io.circe.parser.decode[PersistedSnapshot](snapshotJson).fold(error => fail(error.getMessage), identity)
          _ = assert(snapshot.lastIncludedIndex >= 5L)
          _ = assertEquals(snapshot.formatVersion, PersistedSnapshot.formatVersionV1)
          _ = assert(snapshot.serviceState.responses.contains((tenantId, com.richrobertson.tenure.model.RequestId("req-3"))))
          _ = assert(snapshot.serviceState.leaseState.get(com.richrobertson.tenure.model.ResourceKey(tenantId, ResourceId("resource-1"))).exists(_.fencingToken == 1L))
          _ = assert(snapshot.serviceState.leaseState.get(com.richrobertson.tenure.model.ResourceKey(tenantId, ResourceId("resource-2"))).exists(_.status == LeaseStatus.Released))
          _ = assert(replayedRead.leaseState.get(com.richrobertson.tenure.model.ResourceKey(tenantId, ResourceId("resource-3"))).exists(_.holderId.value == "holder-3"))
          follower <- cluster.nodes.filterNot(_.nodeId == leader.nodeId).headOption.liftTo[IO](new IllegalStateException("missing follower"))
          followerService = cluster.service(follower.nodeId)
          followerRead <- followerService.getLease(GetLeaseRequest(principal, tenantId.value, "resource-1"))
          _ = assert(followerRead.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
          _ <- releaseRestarted
        yield ()
      }
    }
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
      peers <- Resource.eval(IO.blocking(parallelPeers(size)))
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
      nodes.traverse(nodeLeaseRecord).map { records =>
        val statuses = records.map(recordStatus)
        if statuses.forall(_ == expected) then ()
        else throw new IllegalStateException(s"expected all statuses to be $expected, found $statuses")
      }
    }

  private def awaitFollowerView(node: RaftNode[IO], expected: LeaseStatus): IO[Unit] =
    eventually(s"restarted follower catch-up to $expected") {
      nodeLeaseRecord(node).flatMap { record =>
        val status = recordStatus(record)
        if status == expected then IO.unit
        else IO.raiseError(new IllegalStateException(s"expected restarted follower status $expected, found $status"))
      }
    }


  private def nodeLeaseRecord(node: RaftNode[IO]): IO[Option[com.richrobertson.tenure.model.LeaseRecord]] =
    node.readState.map(_.leaseState.get(com.richrobertson.tenure.model.ResourceKey(tenantId, resourceId)))

  private def recordStatus(record: Option[com.richrobertson.tenure.model.LeaseRecord]): LeaseStatus =
    record.fold(LeaseStatus.Absent)(_.status)

  private def eventually[A](label: String, timeout: FiniteDuration = 12.seconds, interval: FiniteDuration = 200.millis)(thunk: IO[A]): IO[A] =
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

  private def retrying[A](label: String, attempts: Int, attemptTimeout: FiniteDuration = 15.seconds)(thunk: => IO[A]): IO[A] =
    thunk.timeoutTo(attemptTimeout, IO.raiseError(new TimeoutException(s"attempt timed out after $attemptTimeout"))).handleErrorWith { error =>
      if attempts <= 1 then IO.raiseError(error)
      else
        IO.sleep(250.millis) *> retrying(label, attempts - 1)(thunk)
    }.adaptError { case error => AssertionError(s"$label failed after retries: ${error.getMessage}") }

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
