package com.richrobertson.tenure.service

import cats.effect.{IO, Ref}
import com.richrobertson.tenure.model.LeaseStatus
import com.richrobertson.tenure.raft.{NodeRole, NotLeader, RaftNode}
import com.richrobertson.tenure.model.{ResourceId, TenantId}
import com.richrobertson.tenure.time.TestClock
import munit.CatsEffectSuite
import java.time.Instant

class LeaseServiceSpec extends CatsEffectSuite:
  private val start = Instant.parse("2026-03-20T12:00:00Z")

  test("getLease returns current active lease") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquired <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      lease <- service.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
    yield
      assert(acquired.isRight)
      assert(lease.isRight)
      assertEquals(lease.toOption.map(_.found), Some(true))
      assertEquals(lease.toOption.map(_.lease.status), Some(LeaseStatus.Active))
  }

  test("getLease returns found false with ABSENT lease when nothing exists") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      lease <- service.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
    yield
      assertEquals(lease.toOption.map(_.found), Some(false))
      assertEquals(lease.toOption.map(_.lease.status), Some(LeaseStatus.Absent))
  }

  test("getLease returns EXPIRED after authoritative expiry without deleting the record") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      _ <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 5, "req-1"))
      _ <- clock.advanceSeconds(5)
      lease <- service.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
    yield
      assertEquals(lease.toOption.map(_.found), Some(true))
      assertEquals(lease.toOption.map(_.lease.status), Some(LeaseStatus.Expired))
  }

  test("expiration works with fake clock") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      first <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 5, "req-1"))
      _ <- clock.advanceSeconds(5)
      second <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-2", 5, "req-2"))
    yield
      assert(first.isRight)
      assert(second.isRight)
  }

  test("reusing the same request_id for the same acquire is idempotent") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      first <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      second <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
    yield assertEquals(second, first)
  }

  test("request_id reuse across different targets is rejected") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      _ <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      second <- service.acquire(AcquireRequest("tenant-a", "resource-2", "holder-1", 15, "req-1"))
    yield assertEquals(second.left.toOption, Some(ServiceError.InvalidRequest("request_id cannot be reused for a different operation or resource")))
  }

  test("renew succeeds before expiry for the correct holder") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      acquired = acquireSuccess(acquiredResult)
      leaseId = acquired.lease.leaseId.fold(fail("expected lease id in acquire result"))(_.value.toString)
      renewed <- service.renew(RenewRequest("tenant-a", "resource-1", leaseId, "holder-1", 30, "req-2"))
    yield
      assert(renewed.isRight)
      assertEquals(renewed.toOption.map(_.lease.expiresAt), Some(Some(start.plusSeconds(30))))
  }

  test("renew fails after authoritative expiry") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 5, "req-1"))
      acquired = acquireSuccess(acquiredResult)
      leaseId = acquired.lease.leaseId.fold(fail("expected lease id in acquire result"))(_.value.toString)
      _ <- clock.advanceSeconds(5)
      renewed <- service.renew(RenewRequest("tenant-a", "resource-1", leaseId, "holder-1", 30, "req-2"))
    yield
      renewed match
        case Left(ServiceError.LeaseExpired(message)) =>
          assert(message.contains("lease for resource"))
        case other =>
          fail(s"expected LeaseExpired after expiry, got: $other")
  }

  test("renew fails for wrong holder") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      acquired = acquireSuccess(acquiredResult)
      leaseId = acquired.lease.leaseId.fold(fail("expected lease id in acquire result"))(_.value.toString)
      renewed <- service.renew(RenewRequest("tenant-a", "resource-1", leaseId, "holder-2", 15, "req-2"))
    yield
      renewed match
        case Left(ServiceError.LeaseMismatch(message)) => assert(message.contains("lease holder or lease id did not match resource"))
        case other                                     => fail(s"expected LeaseMismatch for wrong holder, got: $other")
  }

  test("renew fails for wrong lease id") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      acquired = acquireSuccess(acquiredResult)
      _ = acquired.lease.leaseId.fold(fail("expected lease id in acquire result"))(_.value.toString)
      renewed <- service.renew(RenewRequest("tenant-a", "resource-1", "00000000-0000-0000-0000-000000000999", "holder-1", 15, "req-2"))
    yield assert(renewed.left.exists(_.isInstanceOf[ServiceError.LeaseMismatch]))
  }

  test("release fails for wrong holder") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      acquired = acquireSuccess(acquiredResult)
      leaseId = acquired.lease.leaseId.fold(fail("expected lease id in acquire result"))(_.value.toString)
      released <- service.release(ReleaseRequest("tenant-a", "resource-1", leaseId, "holder-2", "req-2"))
    yield assert(released.left.exists(_.isInstanceOf[ServiceError.LeaseMismatch]))
  }

  test("release succeeds for the correct holder and getLease shows RELEASED") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      acquired = acquireSuccess(acquiredResult)
      leaseId = acquired.lease.leaseId.fold(fail("expected lease id in acquire result"))(_.value.toString)
      released <- service.release(ReleaseRequest("tenant-a", "resource-1", leaseId, "holder-1", "req-2"))
      lease <- service.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
    yield
      assert(released.isRight)
      assertEquals(lease.toOption.map(_.lease.status), Some(LeaseStatus.Released))
  }


  test("replaying committed commands reconstructs lease state and duplicate responses after leadership change") {
    val tenantId = TenantId("tenant-a")
    val resourceId = ResourceId("resource-1")
    val resourceKey = com.richrobertson.tenure.model.ResourceKey(tenantId, resourceId)
    val acquireCtx = RequestContext(tenantId, com.richrobertson.tenure.model.RequestId("req-1"), resourceKey)
    val renewCtx = RequestContext(tenantId, com.richrobertson.tenure.model.RequestId("req-2"), resourceKey)
    val leaseId = com.richrobertson.tenure.model.LeaseId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000123"))
    val acquire = AcquireCommand(acquireCtx, com.richrobertson.tenure.model.ClientId("holder-1"), 15, leaseId, start)
    val renew = RenewCommand(renewCtx, leaseId, com.richrobertson.tenure.model.ClientId("holder-1"), 30, start.plusSeconds(5))

    val originalLeader = List(acquire, renew).foldLeft(ServiceState.empty)(LeaseMaterializer.applyCommand)
    val newLeader = List(acquire, renew).foldLeft(ServiceState.empty)(LeaseMaterializer.applyCommand)

    assertEquals(newLeader.leaseState, originalLeader.leaseState)
    assertEquals(newLeader.responses, originalLeader.responses)
    assertEquals(LeaseMaterializer.replayAcquire(newLeader.responses((tenantId, com.richrobertson.tenure.model.RequestId("req-1"))).result), LeaseMaterializer.replayAcquire(originalLeader.responses((tenantId, com.richrobertson.tenure.model.RequestId("req-1"))).result))
    assertEquals(newLeader.leaseState.viewAt(resourceKey, start.plusSeconds(10)).status, LeaseStatus.Active)
  }


  test("replicated service rejects follower writes with NOT_LEADER") {
    for
      clock <- TestClock.create[IO](start)
      stateRef <- Ref.of[IO, ServiceState](ServiceState.empty)
      raft = StubRaftNode("node-2", submitResult = Left(NotLeader(Some("127.0.0.1:9001"))), canServeReads = false, stateRef = stateRef)
      service = LeaseService.replicated[IO](raft, clock)
      result <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
    yield assertEquals(result.left.toOption, Some(ServiceError.NotLeader("node node-2 is not the leader", Some("127.0.0.1:9001"))))
  }

  test("replicated service rejects follower getLease with NOT_LEADER") {
    for
      clock <- TestClock.create[IO](start)
      stateRef <- Ref.of[IO, ServiceState](ServiceState.empty)
      raft = StubRaftNode("node-2", submitResult = Left(NotLeader(Some("127.0.0.1:9001"))), canServeReads = false, stateRef = stateRef)
      service = LeaseService.replicated[IO](raft, clock)
      result <- service.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
    yield assertEquals(result.left.toOption, Some(ServiceError.NotLeader("node node-2 is not authoritative for reads", Some("127.0.0.1:9001"))))
  }

  test("validation rejects missing request_id") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      result <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, ""))
    yield assertEquals(result.left.toOption, Some(ServiceError.InvalidRequest("request_id must be non-empty")))
  }

  test("validation rejects non-positive ttl") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      result <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 0, "req-1"))
    yield assertEquals(result.left.toOption, Some(ServiceError.InvalidRequest("ttl_seconds must be positive")))
  }

  test("listLeases is tenant scoped") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      _ <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15, "req-1"))
      _ <- service.acquire(AcquireRequest("tenant-b", "resource-1", "holder-2", 15, "req-2"))
      listed <- service.listLeases(TenantId("tenant-a"))
    yield assertEquals(listed.toOption.map(_.leases.map(_.tenantId.value)), Some(List("tenant-a")))
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
