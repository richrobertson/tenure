package com.richrobertson.tenure.service

import cats.effect.IO
import com.richrobertson.tenure.auth.Principal
import com.richrobertson.tenure.model.TenantId
import com.richrobertson.tenure.time.TestClock
import munit.CatsEffectSuite
import java.time.Instant

class FencingTokenSpec extends CatsEffectSuite:
  private val start = Instant.parse("2026-03-20T12:00:00Z")
  private val tenantId = TenantId("tenant-a")
  private val principal = Principal("writer", tenantId)

  test("downstream simulation rejects stale fencing tokens") {
    final case class Downstream(lastAcceptedToken: Long):
      def write(token: Long, payload: String): Either[String, Downstream] =
        Either.cond(token >= lastAcceptedToken, copy(lastAcceptedToken = token), s"stale fencing token $token rejected; current minimum is $lastAcceptedToken")

    for
      clock <- TestClock.create[IO](start)
      service <- LeaseService.inMemory[IO](clock)
      first <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-1", "holder-1", 5, "req-1"))
      _ <- clock.advanceSeconds(5)
      second <- service.acquire(AcquireRequest(principal, tenantId.value, "resource-1", "holder-2", 5, "req-2"))
    yield
      val firstToken = first.toOption.map(_.lease.fencingToken).getOrElse(fail("expected first acquire success"))
      val secondToken = second.toOption.map(_.lease.fencingToken).getOrElse(fail("expected second acquire success"))
      val accepted = Downstream(0L).write(secondToken, "new owner write")
      val rejected = accepted.toOption.getOrElse(fail("expected downstream accept")).write(firstToken, "stale write")

      assertEquals(firstToken, 1L)
      assertEquals(secondToken, 2L)
      assert(rejected.left.exists(_.contains("stale fencing token 1 rejected")))
  }
