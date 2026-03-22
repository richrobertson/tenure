package com.richrobertson.tenure.service

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.richrobertson.tenure.auth.Principal
import com.richrobertson.tenure.group.{GroupId, GroupRuntime}
import com.richrobertson.tenure.model.{LeaseStatus, ResourceId, TenantId}
import com.richrobertson.tenure.observability.Observability
import com.richrobertson.tenure.quota.{TenantQuotaPolicy, TenantQuotaRegistry}
import com.richrobertson.tenure.routing.HashRouter
import com.richrobertson.tenure.time.TestClock
import munit.CatsEffectSuite

import java.time.Instant
import scala.concurrent.duration.*

class RoutedLeaseServiceSpec extends CatsEffectSuite:
  private val start = Instant.parse("2026-03-21T12:00:00Z")
  private val tenantId = TenantId("tenant-a")
  private val principal = Principal("holder", tenantId)
  private val groupIds = Vector(GroupId("group-1"), GroupId("group-2"), GroupId("group-3"))

  test("multi-group routing preserves lease lifecycle and tenant-scoped list behavior") {
    for
      runtime <- inMemoryService(3)
      (service, router, _, clock) = runtime
      resourceA = resourceForGroup(router, GroupId("group-1"), "alpha")
      resourceB = resourceForGroup(router, GroupId("group-2"), "beta")
      acquireA <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "req-a"))
      acquireB <- service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "req-b"))
      leaseA <- service.getLease(GetLeaseRequest(principal, tenantId.value, resourceA.value))
      leaseB <- service.getLease(GetLeaseRequest(principal, tenantId.value, resourceB.value))
      listed <- service.listLeases(ListLeasesRequest(principal, tenantId.value))
      _ <- clock.advanceSeconds(10)
      expiredA <- service.getLease(GetLeaseRequest(principal, tenantId.value, resourceA.value))
    yield
      assert(acquireA.isRight)
      assert(acquireB.isRight)
      assertEquals(leaseA.toOption.map(_.lease.status), Some(LeaseStatus.Active))
      assertEquals(leaseB.toOption.map(_.lease.status), Some(LeaseStatus.Active))
      assertEquals(listed.toOption.map(_.leases.map(_.resourceId.value)), Some(List(resourceA.value, resourceB.value).sorted))
      assertEquals(expiredA.toOption.map(_.lease.status), Some(LeaseStatus.Expired))
  }

  test("tenant-scoped request idempotency remains global across routed groups") {
    for
      runtime <- inMemoryService(3)
      (service, router, _, _) = runtime
      resourceA = resourceForGroup(router, GroupId("group-1"), "dup-a")
      resourceB = resourceForGroup(router, GroupId("group-2"), "dup-b")
      first <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "req-1"))
      duplicate <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "req-1"))
      conflict <- service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "req-1"))
    yield
      assert(first.isRight)
      assertEquals(duplicate, first)
      assertEquals(conflict.left.toOption, Some(ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters")))
  }

  test("concurrent routed acquires preserve tenant-global request-id admission") {
    for
      clock <- TestClock.create[IO](start)
      observability <- Observability.inMemory[IO]
      groups <- groupIds.take(2).toList.traverse(groupId => GroupRuntime.inMemory[IO](groupId, clock, observability))
      router = HashRouter(groupIds.take(2))
      resourceA = resourceForGroup(router, GroupId("group-1"), "serial-a")
      resourceB = resourceForGroup(router, GroupId("group-2"), "serial-b")
      firstStarted <- Deferred[IO, Unit]
      secondStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      secondEntered <- Deferred[IO, Unit]
      gatedGroups = groups.map {
        case group if group.groupId == GroupId("group-1") =>
          wrapAcquire(group)(firstStarted.complete(()).void *> releaseFirst.get)
        case group if group.groupId == GroupId("group-2") =>
          wrapAcquire(group)(secondEntered.complete(()).void)
        case group => group
      }
      service <- LeaseService.routed[IO](router, gatedGroups, clock, observability)
      firstFiber <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "req-serial")).start
      _ <- firstStarted.get
      secondFiber <- (secondStarted.complete(()).void *> service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "req-serial"))).start
      _ <- secondStarted.get
      secondObservedBeforeRelease <- assertStillBlocked(secondEntered)
      _ = assertEquals(secondObservedBeforeRelease, Right(()))
      _ <- releaseFirst.complete(()).void
      first <- firstFiber.joinWithNever
      second <- secondFiber.joinWithNever
    yield
      assert(first.isRight)
      assertEquals(second.left.toOption, Some(ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters")))
  }

  test("concurrent routed acquires preserve tenant-global active lease quotas") {
    val quotas = TenantQuotaRegistry(TenantQuotaPolicy(maxActiveLeases = 1000, maxTtlSeconds = 300), Map(tenantId -> TenantQuotaPolicy(maxActiveLeases = 1, maxTtlSeconds = 300)))
    for
      clock <- TestClock.create[IO](start)
      observability <- Observability.inMemory[IO]
      groups <- groupIds.take(2).toList.traverse(groupId => GroupRuntime.inMemory[IO](groupId, clock, quotas, com.richrobertson.tenure.auth.Authorization.perTenant, observability))
      router = HashRouter(groupIds.take(2))
      resourceA = resourceForGroup(router, GroupId("group-1"), "quota-a")
      resourceB = resourceForGroup(router, GroupId("group-2"), "quota-b")
      firstStarted <- Deferred[IO, Unit]
      secondStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      secondEntered <- Deferred[IO, Unit]
      gatedGroups = groups.map {
        case group if group.groupId == GroupId("group-1") =>
          wrapAcquire(group)(firstStarted.complete(()).void *> releaseFirst.get)
        case group if group.groupId == GroupId("group-2") =>
          wrapAcquire(group)(secondEntered.complete(()).void)
        case group => group
      }
      service <- LeaseService.routed[IO](router, gatedGroups, clock, quotas, com.richrobertson.tenure.auth.Authorization.perTenant, observability)
      firstFiber <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "req-1")).start
      _ <- firstStarted.get
      secondFiber <- (secondStarted.complete(()).void *> service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "req-2"))).start
      _ <- secondStarted.get
      secondObservedBeforeRelease <- assertStillBlocked(secondEntered)
      _ = assertEquals(secondObservedBeforeRelease, Right(()))
      _ <- releaseFirst.complete(()).void
      first <- firstFiber.joinWithNever
      second <- secondFiber.joinWithNever
    yield
      assert(first.isRight)
      assert(second.left.exists(_.isInstanceOf[ServiceError.QuotaExceeded]))
  }

  test("concurrent routed acquires for different tenants do not share the same admission lock") {
    val otherTenantId = TenantId("tenant-b")
    val otherPrincipal = Principal("holder-b", otherTenantId)
    for
      clock <- TestClock.create[IO](start)
      observability <- Observability.inMemory[IO]
      groups <- groupIds.take(2).toList.traverse(groupId => GroupRuntime.inMemory[IO](groupId, clock, observability))
      router = HashRouter(groupIds.take(2))
      resourceA = resourceForGroup(router, tenantId, GroupId("group-1"), "tenant-a")
      resourceB = resourceForGroup(router, otherTenantId, GroupId("group-2"), "tenant-b")
      firstStarted <- Deferred[IO, Unit]
      secondStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      secondEntered <- Deferred[IO, Unit]
      gatedGroups = groups.map {
        case group if group.groupId == GroupId("group-1") =>
          wrapAcquire(group)(firstStarted.complete(()).void *> releaseFirst.get)
        case group if group.groupId == GroupId("group-2") =>
          wrapAcquire(group)(secondEntered.complete(()).void)
        case group => group
      }
      service <- LeaseService.routed[IO](router, gatedGroups, clock, observability)
      firstFiber <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "req-tenant-a")).start
      _ <- firstStarted.get
      secondFiber <- (secondStarted.complete(()).void *> service.acquire(AcquireRequest(otherPrincipal, otherTenantId.value, resourceB.value, "holder-b", 10, "req-tenant-b"))).start
      _ <- secondStarted.get
      _ <- secondEntered.get.timeoutTo(1.second, IO.raiseError(new AssertionError("expected different-tenant acquire to reach group-local execution before the first tenant releases")))
      _ <- releaseFirst.complete(()).void
      first <- firstFiber.joinWithNever
      second <- secondFiber.joinWithNever
    yield
      assert(first.isRight)
      assert(second.isRight)
  }

  test("concurrent routed renews preserve tenant-global request-id admission") {
    for
      runtime <- inMemoryGroups(2)
      (groups, router, observability, clock) = runtime
      service <- LeaseService.routed[IO](router, groups, clock, observability)
      resourceA = resourceForGroup(router, GroupId("group-1"), "renew-a")
      resourceB = resourceForGroup(router, GroupId("group-2"), "renew-b")
      acquiredA <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "acq-a"))
      acquiredB <- service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "acq-b"))
      leaseIdA = acquiredA.toOption.flatMap(_.lease.leaseId).map(_.value.toString).getOrElse(fail("expected first lease"))
      leaseIdB = acquiredB.toOption.flatMap(_.lease.leaseId).map(_.value.toString).getOrElse(fail("expected second lease"))
      firstStarted <- Deferred[IO, Unit]
      secondStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      secondEntered <- Deferred[IO, Unit]
      gatedGroups = groups.map {
        case group if group.groupId == GroupId("group-1") =>
          wrapGroup(group, beforeRenew = firstStarted.complete(()).void *> releaseFirst.get)
        case group if group.groupId == GroupId("group-2") =>
          wrapGroup(group, beforeRenew = secondEntered.complete(()).void)
        case group => group
      }
      lockedService <- LeaseService.routed[IO](router, gatedGroups, clock, observability)
      firstFiber <- lockedService.renew(RenewRequest(principal, tenantId.value, resourceA.value, leaseIdA, "holder-a", 10, "renew-req")).start
      _ <- firstStarted.get
      secondFiber <- (secondStarted.complete(()).void *> lockedService.renew(RenewRequest(principal, tenantId.value, resourceB.value, leaseIdB, "holder-b", 10, "renew-req"))).start
      _ <- secondStarted.get
      secondObservedBeforeRelease <- assertStillBlocked(secondEntered)
      _ = assertEquals(secondObservedBeforeRelease, Right(()))
      _ <- releaseFirst.complete(()).void
      first <- firstFiber.joinWithNever
      second <- secondFiber.joinWithNever
    yield
      assert(first.isRight)
      assertEquals(second.left.toOption, Some(ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters")))
  }

  test("concurrent routed releases preserve tenant-global request-id admission") {
    for
      runtime <- inMemoryGroups(2)
      (groups, router, observability, clock) = runtime
      service <- LeaseService.routed[IO](router, groups, clock, observability)
      resourceA = resourceForGroup(router, GroupId("group-1"), "release-a")
      resourceB = resourceForGroup(router, GroupId("group-2"), "release-b")
      acquiredA <- service.acquire(AcquireRequest(principal, tenantId.value, resourceA.value, "holder-a", 10, "acq-a"))
      acquiredB <- service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "acq-b"))
      leaseIdA = acquiredA.toOption.flatMap(_.lease.leaseId).map(_.value.toString).getOrElse(fail("expected first lease"))
      leaseIdB = acquiredB.toOption.flatMap(_.lease.leaseId).map(_.value.toString).getOrElse(fail("expected second lease"))
      firstStarted <- Deferred[IO, Unit]
      secondStarted <- Deferred[IO, Unit]
      releaseFirst <- Deferred[IO, Unit]
      secondEntered <- Deferred[IO, Unit]
      gatedGroups = groups.map {
        case group if group.groupId == GroupId("group-1") =>
          wrapGroup(group, beforeRelease = firstStarted.complete(()).void *> releaseFirst.get)
        case group if group.groupId == GroupId("group-2") =>
          wrapGroup(group, beforeRelease = secondEntered.complete(()).void)
        case group => group
      }
      lockedService <- LeaseService.routed[IO](router, gatedGroups, clock, observability)
      firstFiber <- lockedService.release(ReleaseRequest(principal, tenantId.value, resourceA.value, leaseIdA, "holder-a", "release-req")).start
      _ <- firstStarted.get
      secondFiber <- (secondStarted.complete(()).void *> lockedService.release(ReleaseRequest(principal, tenantId.value, resourceB.value, leaseIdB, "holder-b", "release-req"))).start
      _ <- secondStarted.get
      secondObservedBeforeRelease <- assertStillBlocked(secondEntered)
      _ = assertEquals(secondObservedBeforeRelease, Right(()))
      _ <- releaseFirst.complete(()).void
      first <- firstFiber.joinWithNever
      second <- secondFiber.joinWithNever
    yield
      assert(first.isRight)
      assertEquals(second.left.toOption, Some(ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters")))
  }

  test("routed duplicate replays and validation failures remain observable") {
    for
      runtime <- inMemoryService(2)
      (service, router, observability, _) = runtime
      resource = resourceForGroup(router, GroupId("group-1"), "observed-dup")
      first <- service.acquire(AcquireRequest(principal, tenantId.value, resource.value, "holder-a", 10, "req-dup"))
      duplicate <- service.acquire(AcquireRequest(principal, tenantId.value, resource.value, "holder-a", 10, "req-dup"))
      invalid <- service.acquire(AcquireRequest(principal, tenantId.value, resource.value, "", 10, "req-invalid"))
      snapshot <- observability.snapshot
    yield
      assertEquals(duplicate, first)
      assertEquals(invalid.left.toOption, Some(ServiceError.InvalidRequest("holder_id must be non-empty")))
      assert(snapshot.counters.exists(sample => sample.metric.name == "duplicate_requests_total" && sample.metric.labels.get("node_id").contains("routed") && sample.value >= 1L))
      assert(snapshot.events.exists(event => event.eventType == "lease.request.result" && event.requestId.contains("req-dup") && event.result.contains("duplicate_replay")))
      assert(snapshot.events.exists(event => event.eventType == "lease.request.result" && event.errorCode.contains("INVALID_ARGUMENT")))
  }

  test("observability signals include group_id labels and fields") {
    for
      runtime <- inMemoryService(2)
      (service, router, observability, _) = runtime
      resource = resourceForGroup(router, GroupId("group-2"), "observable")
      expectedGroupId = router.route(tenantId, resource).groupId.value
      _ <- service.acquire(AcquireRequest(principal, tenantId.value, resource.value, "holder-a", 10, "req-1"))
      _ <- service.getLease(GetLeaseRequest(principal, tenantId.value, resource.value))
      snapshot <- observability.snapshot
    yield
      assert(snapshot.counters.exists(sample => sample.metric.name == "service_requests_total" && sample.metric.labels.get("group_id").contains(expectedGroupId)))
      assert(snapshot.events.exists(event => event.eventType == "lease.request.result" && event.fields.get("group_id").contains(expectedGroupId)))
      assert(snapshot.events.exists(event => event.eventType == "routing.decision" && event.fields.get("group_id").contains(expectedGroupId)))
  }

  test("routed service construction rejects duplicate group ids") {
    for
      clock <- TestClock.create[IO](start)
      observability <- Observability.inMemory[IO]
      group <- GroupRuntime.inMemory[IO](GroupId("group-1"), clock, observability)
      result <- LeaseService.routed[IO](HashRouter(Vector(GroupId("group-1"))), List(group, group), clock, observability).attempt
    yield
      assert(result.left.exists(_.getMessage.contains("duplicate group ids")))
  }

  private def inMemoryService(groupCount: Int): IO[(LeaseService[IO], HashRouter, com.richrobertson.tenure.observability.InMemoryObservability[IO], TestClock[IO])] =
    for
      runtime <- inMemoryGroups(groupCount)
      (groups, router, observability, clock) = runtime
      service <- LeaseService.routed[IO](router, groups, clock, observability)
    yield (service, router, observability, clock)

  private def inMemoryGroups(groupCount: Int): IO[(List[GroupRuntime[IO]], HashRouter, com.richrobertson.tenure.observability.InMemoryObservability[IO], TestClock[IO])] =
    for
      clock <- TestClock.create[IO](start)
      observability <- Observability.inMemory[IO]
      selectedGroupIds = (1 to groupCount).toVector.map(idx => GroupId(s"group-$idx"))
      groups <- selectedGroupIds.toList.traverse(groupId => GroupRuntime.inMemory[IO](groupId, clock, observability))
      router = HashRouter(selectedGroupIds)
    yield (groups, router, observability, clock)

  private def wrapAcquire(group: GroupRuntime[IO])(beforeAcquire: IO[Unit]): GroupRuntime[IO] =
    wrapGroup(group, beforeAcquire = beforeAcquire)

  private def assertStillBlocked(signal: Deferred[IO, Unit]): IO[Either[Unit, Unit]] =
    signal.tryGet.map {
      case Some(_) => Left(())
      case None    => Right(())
    }

  private def wrapGroup(
      group: GroupRuntime[IO],
      beforeAcquire: IO[Unit] = IO.unit,
      beforeRenew: IO[Unit] = IO.unit,
      beforeRelease: IO[Unit] = IO.unit
  ): GroupRuntime[IO] =
    new GroupRuntime[IO]:
      override def groupId: GroupId = group.groupId
      override def readState: IO[ServiceState] = group.readState
      override def service: LeaseService[IO] =
        new LeaseService[IO]:
          override def acquire(request: AcquireRequest): IO[Either[ServiceError, AcquireResult]] =
            beforeAcquire *> group.service.acquire(request)

          override def renew(request: RenewRequest): IO[Either[ServiceError, RenewResult]] =
            beforeRenew *> group.service.renew(request)

          override def release(request: ReleaseRequest): IO[Either[ServiceError, ReleaseResult]] =
            beforeRelease *> group.service.release(request)

          override def getLease(request: GetLeaseRequest): IO[Either[ServiceError, GetLeaseResult]] =
            group.service.getLease(request)

          override def listLeases(request: ListLeasesRequest): IO[Either[ServiceError, ListLeasesResult]] =
            group.service.listLeases(request)

  private def resourceForGroup(router: HashRouter, groupId: GroupId, prefix: String): ResourceId =
    resourceForGroup(router, tenantId, groupId, prefix)

  private def resourceForGroup(router: HashRouter, tenantId: TenantId, groupId: GroupId, prefix: String): ResourceId =
    (1 to 512)
      .iterator
      .map(idx => ResourceId(s"$prefix-$idx"))
      .find(resourceId => router.route(tenantId, resourceId).groupId == groupId)
      .getOrElse(fail(s"expected to find resource for ${groupId.value}"))
