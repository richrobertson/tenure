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
      secondFiber <- service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "req-serial")).start
      _ <- IO.sleep(scala.concurrent.duration.DurationInt(200).millis)
      secondObservedBeforeRelease <- secondEntered.tryGet
      _ = assertEquals(secondObservedBeforeRelease, None)
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
      secondFiber <- service.acquire(AcquireRequest(principal, tenantId.value, resourceB.value, "holder-b", 10, "req-2")).start
      _ <- IO.sleep(scala.concurrent.duration.DurationInt(200).millis)
      secondObservedBeforeRelease <- secondEntered.tryGet
      _ = assertEquals(secondObservedBeforeRelease, None)
      _ <- releaseFirst.complete(()).void
      first <- firstFiber.joinWithNever
      second <- secondFiber.joinWithNever
    yield
      assert(first.isRight)
      assert(second.left.exists(_.isInstanceOf[ServiceError.QuotaExceeded]))
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

  private def inMemoryService(groupCount: Int): IO[(LeaseService[IO], HashRouter, com.richrobertson.tenure.observability.InMemoryObservability[IO], TestClock[IO])] =
    for
      clock <- TestClock.create[IO](start)
      observability <- Observability.inMemory[IO]
      selectedGroupIds = (1 to groupCount).toVector.map(idx => GroupId(s"group-$idx"))
      groups <- selectedGroupIds.toList.traverse(groupId => GroupRuntime.inMemory[IO](groupId, clock, observability))
      router = HashRouter(selectedGroupIds)
      service <- LeaseService.routed[IO](router, groups, clock, observability)
    yield (service, router, observability, clock)

  private def wrapAcquire(group: GroupRuntime[IO])(beforeAcquire: IO[Unit]): GroupRuntime[IO] =
    new GroupRuntime[IO]:
      override def groupId: GroupId = group.groupId
      override def readState: IO[ServiceState] = group.readState
      override def service: LeaseService[IO] =
        new LeaseService[IO]:
          override def acquire(request: AcquireRequest): IO[Either[ServiceError, AcquireResult]] =
            beforeAcquire *> group.service.acquire(request)

          override def renew(request: RenewRequest): IO[Either[ServiceError, RenewResult]] =
            group.service.renew(request)

          override def release(request: ReleaseRequest): IO[Either[ServiceError, ReleaseResult]] =
            group.service.release(request)

          override def getLease(request: GetLeaseRequest): IO[Either[ServiceError, GetLeaseResult]] =
            group.service.getLease(request)

          override def listLeases(request: ListLeasesRequest): IO[Either[ServiceError, ListLeasesResult]] =
            group.service.listLeases(request)

  private def resourceForGroup(router: HashRouter, groupId: GroupId, prefix: String): ResourceId =
    (1 to 512)
      .iterator
      .map(idx => ResourceId(s"$prefix-$idx"))
      .find(resourceId => router.route(tenantId, resourceId).groupId == groupId)
      .getOrElse(fail(s"expected to find resource for ${groupId.value}"))
