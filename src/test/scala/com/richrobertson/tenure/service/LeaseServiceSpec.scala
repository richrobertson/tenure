package com.richrobertson.tenure.service

import cats.effect.IO
import com.richrobertson.tenure.model.LeaseStatus
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
      assertEquals(lease.found, true)
      assertEquals(lease.lease.status, LeaseStatus.Active)
  }

  test("getLease returns found false with ABSENT lease when nothing exists") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      lease <- service.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
    yield
      assertEquals(lease.found, false)
      assertEquals(lease.lease.status, LeaseStatus.Absent)
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
        case Left(ServiceError.LeaseMismatch(message)) =>
          assert(message.contains("lease holder or lease id did not match resource"))
        case other =>
          fail(s"expected LeaseMismatch for wrong holder, got: $other")
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

  private def acquireSuccess(result: Either[ServiceError, AcquireResult]): AcquireResult =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"expected successful acquire, got $error")
