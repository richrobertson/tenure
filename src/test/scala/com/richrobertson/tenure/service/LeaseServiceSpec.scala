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
      acquired <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15))
      lease <- service.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
    yield
      assert(acquired.isRight)
      assertEquals(lease.map(_.status), Some(LeaseStatus.Active))
  }

  test("expiration works with fake clock") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      first <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 5))
      _ <- clock.advanceSeconds(5)
      second <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-2", 5))
    yield
      assert(first.isRight)
      assert(second.isRight)
  }

  test("renew fails for wrong holder") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15))
      acquired = acquiredResult.toOption.getOrElse(fail("expected successful acquire"))
      renewed <- service.renew(RenewRequest("tenant-a", "resource-1", acquired.leaseId.value.toString, "holder-2", 15))
    yield assertEquals(renewed.left.toOption, Some(ServiceError.LeaseMismatch("lease holder or lease id did not match resource ResourceKey(TenantId(tenant-a),ResourceId(resource-1))")))
  }

  test("release fails for wrong holder") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      acquiredResult <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 15))
      acquired = acquiredResult.toOption.getOrElse(fail("expected successful acquire"))
      released <- service.release(ReleaseRequest("tenant-a", "resource-1", acquired.leaseId.value.toString, "holder-2"))
    yield assert(released.left.exists(_.isInstanceOf[ServiceError.LeaseMismatch]))
  }

  test("validation rejects non-positive ttl") {
    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      result <- service.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 0))
    yield assertEquals(result.left.toOption, Some(ServiceError.InvalidRequest("ttl_seconds must be positive")))
  }
