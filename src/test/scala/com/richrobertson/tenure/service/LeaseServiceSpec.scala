package com.richrobertson.tenure.service

import cats.effect.{IO, Ref}
import com.richrobertson.tenure.auth.Principal
import com.richrobertson.tenure.model.{LeaseStatus, ResourceId, TenantId}
import com.richrobertson.tenure.quota.{TenantQuotaPolicy, TenantQuotaRegistry}
import com.richrobertson.tenure.raft.{NodeRole, NotLeader, RaftNode}
import com.richrobertson.tenure.time.TestClock
import munit.CatsEffectSuite
import java.time.Instant

class LeaseServiceSpec extends CatsEffectSuite:
  private val start = Instant.parse("2026-03-20T12:00:00Z")
  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")
  private val principalA = Principal("user-a", tenantA)
  private val principalB = Principal("user-b", tenantB)

  test("getLease returns current active lease") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquired <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      lease <- service.getLease(GetLeaseRequest(principalA, "tenant-a", "resource-1"))
    yield
      assert(acquired.isRight)
      assert(lease.isRight)
      assertEquals(lease.toOption.map(_.found), Some(true))
      assertEquals(lease.toOption.map(_.lease.status), Some(LeaseStatus.Active))
  }

  test("repeated acquire retry replays original result deterministically") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      first <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      second <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
    yield assertEquals(second, first)
  }

  test("request_id reuse with mismatched acquire parameters is rejected") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      _ <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      second <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-2", 15, "req-1"))
    yield assertEquals(second.left.toOption, Some(ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters")))
  }

  test("request_id reuse across different targets is rejected") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      _ <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      second <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-2", "holder-1", 15, "req-1"))
    yield assertEquals(second.left.toOption, Some(ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters")))
  }

  test("renew retry is deterministic") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      leaseId = acquireSuccess(acquiredResult).lease.leaseId.fold(fail("expected lease id"))(_.value.toString)
      first <- service.renew(RenewRequest(principalA, "tenant-a", "resource-1", leaseId, "holder-1", 30, "req-2"))
      second <- service.renew(RenewRequest(principalA, "tenant-a", "resource-1", leaseId, "holder-1", 30, "req-2"))
    yield assertEquals(second, first)
  }

  test("release retry is deterministic") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      leaseId = acquireSuccess(acquiredResult).lease.leaseId.fold(fail("expected lease id"))(_.value.toString)
      first <- service.release(ReleaseRequest(principalA, "tenant-a", "resource-1", leaseId, "holder-1", "req-2"))
      second <- service.release(ReleaseRequest(principalA, "tenant-a", "resource-1", leaseId, "holder-1", "req-2"))
    yield assertEquals(second, first)
  }

  test("quota rejects acquire when tenant exceeds max active leases") {
    val quotas = TenantQuotaRegistry(TenantQuotaPolicy(1000, 300), Map(tenantA -> TenantQuotaPolicy(1, 300)))
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock, quotas)
      _ <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      second <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-2", "holder-2", 15, "req-2"))
    yield assert(second.left.exists(_.isInstanceOf[ServiceError.QuotaExceeded]))
  }

  test("quota rejects ttl above tenant max on acquire and renew") {
    val quotas = TenantQuotaRegistry(TenantQuotaPolicy(1000, 300), Map(tenantA -> TenantQuotaPolicy(5, 10)))
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock, quotas)
      tooLongAcquire <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 11, "req-1"))
      acquired <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 10, "req-2"))
      leaseId = acquireSuccess(acquired).lease.leaseId.fold(fail("expected lease id"))(_.value.toString)
      tooLongRenew <- service.renew(RenewRequest(principalA, "tenant-a", "resource-1", leaseId, "holder-1", 11, "req-3"))
    yield
      assert(tooLongAcquire.left.exists(_.isInstanceOf[ServiceError.QuotaExceeded]))
      assert(tooLongRenew.left.exists(_.isInstanceOf[ServiceError.QuotaExceeded]))
  }

  test("same resource name remains isolated across tenants") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      _ <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-a", 15, "req-1"))
      _ <- service.acquire(AcquireRequest(principalB, "tenant-b", "resource-1", "holder-b", 15, "req-1"))
      leaseA <- service.getLease(GetLeaseRequest(principalA, "tenant-a", "resource-1"))
      leaseB <- service.getLease(GetLeaseRequest(principalB, "tenant-b", "resource-1"))
    yield
      assertEquals(leaseA.toOption.flatMap(_.lease.holderId.map(_.value)), Some("holder-a"))
      assertEquals(leaseB.toOption.flatMap(_.lease.holderId.map(_.value)), Some("holder-b"))
  }

  test("cross-tenant authorization misuse is rejected for writes and reads") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquire <- service.acquire(AcquireRequest(principalB, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      read <- service.getLease(GetLeaseRequest(principalB, "tenant-a", "resource-1"))
    yield
      assertEquals(acquire.left.toOption, Some(ServiceError.Forbidden("principal user-b cannot access tenant tenant-a")))
      assertEquals(read.left.toOption, Some(ServiceError.Forbidden("principal user-b cannot access tenant tenant-a")))
  }

  test("fencing token increases when a lease turns over to a new holder") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      first <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 5, "req-1"))
      _ <- clock.advanceSeconds(5)
      second <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-2", 5, "req-2"))
    yield
      assertEquals(first.toOption.map(_.lease.fencingToken), Some(1L))
      assertEquals(second.toOption.map(_.lease.fencingToken), Some(2L))
  }

  test("replaying committed commands preserves dedupe behavior and fencing progression") {
    val resourceKey = com.richrobertson.tenure.model.ResourceKey(tenantA, ResourceId("resource-1"))
    val leaseId1 = com.richrobertson.tenure.model.LeaseId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000123"))
    val leaseId2 = com.richrobertson.tenure.model.LeaseId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000124"))
    val acquire1 = AcquireCommand(RequestContext(tenantA, com.richrobertson.tenure.model.RequestId("req-1"), resourceKey), com.richrobertson.tenure.model.ClientId("holder-1"), 5, leaseId1, start)
    val retry1 = AcquireCommand(RequestContext(tenantA, com.richrobertson.tenure.model.RequestId("req-1"), resourceKey), com.richrobertson.tenure.model.ClientId("holder-1"), 5, leaseId2, start.plusSeconds(1))
    val acquire2 = AcquireCommand(RequestContext(tenantA, com.richrobertson.tenure.model.RequestId("req-2"), resourceKey), com.richrobertson.tenure.model.ClientId("holder-2"), 5, leaseId2, start.plusSeconds(5))
    val quotas = TenantQuotaRegistry.default

    val replayed = List(acquire1, retry1, acquire2).foldLeft(ServiceState.empty) { case (state, command) =>
      LeaseMaterializer.applyCommand(state, command, quotas)
    }

    val storedRetry = replayed.responses((tenantA, com.richrobertson.tenure.model.RequestId("req-1"))).result
    val latestView = replayed.leaseState.viewAt(resourceKey, start.plusSeconds(6))

    assertEquals(LeaseMaterializer.replayAcquire(storedRetry).flatMap(_.toOption).map(_.lease.leaseId), Some(Some(leaseId1)))
    assertEquals(latestView.fencingToken, 2L)
    assertEquals(latestView.holderId.map(_.value), Some("holder-2"))
  }

  test("replicated service rejects follower writes and reads with NOT_LEADER") {
    for
      clock <- TestClock.create[IO](start)
      stateRef <- Ref.of[IO, ServiceState](ServiceState.empty)
      raft = StubRaftNode("node-2", submitResult = Left(NotLeader(Some("127.0.0.1:9001"))), canServeReads = false, stateRef = stateRef)
      service = LeaseService.replicated[IO](raft, clock)
      write <- service.acquire(AcquireRequest(principalA, "tenant-a", "resource-1", "holder-1", 15, "req-1"))
      read <- service.getLease(GetLeaseRequest(principalA, "tenant-a", "resource-1"))
    yield
      assertEquals(write.left.toOption, Some(ServiceError.NotLeader("node node-2 is not the leader", Some("127.0.0.1:9001"))))
      assertEquals(read.left.toOption, Some(ServiceError.NotLeader("node node-2 is not authoritative for reads", Some("127.0.0.1:9001"))))
  }

  private def acquireSuccess(result: Either[ServiceError, AcquireResult]): AcquireResult =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"expected successful acquire, got $error")

  private final case class StubRaftNode(
      nodeId: String,
      submitResult: Either[NotLeader, StoredResult],
      canServeReads: Boolean,
      stateRef: Ref[IO, ServiceState]
  ) extends RaftNode[IO]:
    override def submit(command: ReplicatedCommand): IO[Either[NotLeader, StoredResult]] = IO.pure(submitResult)
    override def role: IO[NodeRole] = IO.pure(if canServeReads then NodeRole.Leader else NodeRole.Follower)
    override def leaderHint: IO[Option[String]] = IO.pure(submitResult.left.toOption.flatMap(_.leaderHint))
    override def readState: IO[ServiceState] = stateRef.get
    override def canServeLeaderReads: IO[Boolean] = IO.pure(canServeReads)
    override def shutdown: IO[Unit] = IO.unit
