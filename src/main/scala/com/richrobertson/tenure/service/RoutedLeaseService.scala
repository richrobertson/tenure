package com.richrobertson.tenure.service

import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.richrobertson.tenure.auth.Authorization
import com.richrobertson.tenure.group.{GroupId, GroupRuntime}
import com.richrobertson.tenure.model.{ResourceKey, TenantId}
import com.richrobertson.tenure.observability.{LogEvent, Observability}
import com.richrobertson.tenure.quota.TenantQuotaRegistry
import com.richrobertson.tenure.routing.Router
import com.richrobertson.tenure.time.Clock

private final case class RoutedLeaseService[F[_]: Async](
    router: Router,
    runtimes: Map[GroupId, GroupRuntime[F]],
    clock: Clock[F],
    quotas: TenantQuotaRegistry,
    authorization: Authorization,
    observability: Observability[F],
    admissionLock: Mutex[F]
) extends LeaseService[F]
    with ValidationSupport:
  private val orderedGroupIds = router.groups.distinct
  require(orderedGroupIds.nonEmpty, "routed service requires at least one group")
  require(orderedGroupIds.forall(runtimes.contains), s"router referenced missing group ids: ${orderedGroupIds.filterNot(runtimes.contains)}")

  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request, authorization).fold(
      err => observeEarlyResult("acquire", None, Left(err).map(_ => ())).as(err.asLeft[AcquireResult]),
      valid =>
        route(valid.resourceKey, "acquire").flatMap { decision =>
          // Routed acquires need one admission critical section so tenant-global request IDs
          // and active-lease quotas remain correct across groups.
          admissionLock.lock.surround {
            for
              duplicate <- duplicateAcquire(valid.requestContext, valid.fingerprint)
              result <- duplicate match
                case Some(DuplicateCheck.Replay(existing)) => observeEarlyResult("acquire", Some(valid.requestContext), existing.map(_ => ()), dedupe = true).as(existing)
                case Some(DuplicateCheck.Reject(error)) => observeEarlyResult("acquire", Some(valid.requestContext), Left(error).map(_ => ())).as(error.asLeft[AcquireResult])
                case None =>
                  validateGlobalActiveLeaseQuota(valid.requestContext.tenantId).flatMap {
                    case Some(error) => observeEarlyResult("acquire", Some(valid.requestContext), Left(error).map(_ => ())).as(error.asLeft[AcquireResult])
                    case None        => runtime(decision.groupId).service.acquire(request)
                  }
            yield result
          }
        }
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request, authorization).fold(
      err => observeEarlyResult("renew", None, Left(err).map(_ => ())).as(err.asLeft[RenewResult]),
      valid =>
        route(valid.resourceKey, "renew").flatMap { decision =>
          admissionLock.lock.surround {
            for
              duplicate <- duplicateRenew(valid.requestContext, valid.fingerprint)
              result <- duplicate match
                case Some(DuplicateCheck.Replay(existing)) => observeEarlyResult("renew", Some(valid.requestContext), existing.map(_ => ()), dedupe = true).as(existing)
                case Some(DuplicateCheck.Reject(error)) => observeEarlyResult("renew", Some(valid.requestContext), Left(error).map(_ => ())).as(error.asLeft[RenewResult])
                case None => runtime(decision.groupId).service.renew(request)
            yield result
          }
        }
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request, authorization).fold(
      err => observeEarlyResult("release", None, Left(err).map(_ => ())).as(err.asLeft[ReleaseResult]),
      valid =>
        route(valid.resourceKey, "release").flatMap { decision =>
          admissionLock.lock.surround {
            for
              duplicate <- duplicateRelease(valid.requestContext, valid.fingerprint)
              result <- duplicate match
                case Some(DuplicateCheck.Replay(existing)) => observeEarlyResult("release", Some(valid.requestContext), existing.map(_ => ()), dedupe = true).as(existing)
                case Some(DuplicateCheck.Reject(error)) => observeEarlyResult("release", Some(valid.requestContext), Left(error).map(_ => ())).as(error.asLeft[ReleaseResult])
                case None => runtime(decision.groupId).service.release(request)
            yield result
          }
        }
    )

  override def getLease(request: GetLeaseRequest): F[Either[ServiceError, GetLeaseResult]] =
    validateRead(request, authorization).fold(
      err => observeEarlyResult("get", None, Left(err).map(_ => ())).as(err.asLeft[GetLeaseResult]),
      valid => route(valid.resourceKey, "get").flatMap(decision => runtime(decision.groupId).service.getLease(request))
    )

  override def listLeases(request: ListLeasesRequest): F[Either[ServiceError, ListLeasesResult]] =
    validateList(request, authorization).fold(
      err => observeEarlyResult("list", None, Left(err).map(_ => ())).as(err.asLeft[ListLeasesResult]),
      tenantId =>
        observeBroadcast("list", tenantId) *>
          orderedGroupIds.traverse(groupId => runtime(groupId).service.listLeases(request)).map { results =>
            results.collectFirst { case Left(error) => Left(error) }.getOrElse {
              Right(ListLeasesResult(results.collect { case Right(result) => result.leases }.flatten.toList.sortBy(_.resourceId.value)))
            }
          }
    )

  private def runtime(groupId: GroupId): GroupRuntime[F] =
    runtimes.getOrElse(groupId, throw new IllegalStateException(s"missing runtime for ${groupId.value}"))

  private def route(resourceKey: ResourceKey, operation: String): F[com.richrobertson.tenure.routing.RoutingDecision] =
    val decision = router.route(resourceKey)
    clock.now.flatMap { now =>
      observability.incrementCounter("routing_decisions_total", Map("operation" -> operation, "group_id" -> decision.groupId.value)) *>
        observability.log(
          LogEvent(
            now.toEpochMilli,
            "INFO",
            "routing.decision",
            s"$operation routed to ${decision.groupId.value}",
            tenantId = Some(resourceKey.tenantId.value),
            resourceId = Some(resourceKey.resourceId.value),
            fields = Map("group_id" -> decision.groupId.value, "operation" -> operation)
          )
        ).as(decision)
    }

  private def observeBroadcast(operation: String, tenantId: TenantId): F[Unit] =
    clock.now.flatMap { now =>
      observability.incrementCounter("routing_broadcasts_total", Map("operation" -> operation)) *>
        observability.log(
          LogEvent(
            now.toEpochMilli,
            "INFO",
            "routing.broadcast",
            s"$operation fan-out across ${orderedGroupIds.size} groups",
            tenantId = Some(tenantId.value),
            fields = Map("group_count" -> orderedGroupIds.size.toString, "operation" -> operation)
          )
        )
    }

  private def observeEarlyResult(operation: String, requestContext: Option[RequestContext], result: Either[ServiceError, Unit], dedupe: Boolean = false): F[Unit] =
    ServiceObservability.recordResult(observability, clock, "routed", operation, requestContext, result, dedupe)

  private def duplicateAcquire(requestContext: RequestContext, fingerprint: RequestFingerprint): F[Option[DuplicateCheck[AcquireResult]]] =
    lookupStoredResult(requestContext, fingerprint).map {
      case Left(error) => Some(DuplicateCheck.Reject(error))
      case Right(Some(stored)) => Some(DuplicateCheck.Replay(LeaseMaterializer.replayAcquire(stored.result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))))
      case Right(None) => None
    }

  private def duplicateRenew(requestContext: RequestContext, fingerprint: RequestFingerprint): F[Option[DuplicateCheck[RenewResult]]] =
    lookupStoredResult(requestContext, fingerprint).map {
      case Left(error) => Some(DuplicateCheck.Reject(error))
      case Right(Some(stored)) => Some(DuplicateCheck.Replay(LeaseMaterializer.replayRenew(stored.result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))))
      case Right(None) => None
    }

  private def duplicateRelease(requestContext: RequestContext, fingerprint: RequestFingerprint): F[Option[DuplicateCheck[ReleaseResult]]] =
    lookupStoredResult(requestContext, fingerprint).map {
      case Left(error) => Some(DuplicateCheck.Reject(error))
      case Right(Some(stored)) => Some(DuplicateCheck.Replay(LeaseMaterializer.replayRelease(stored.result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))))
      case Right(None) => None
    }

  private def lookupStoredResult(
      requestContext: RequestContext,
      fingerprint: RequestFingerprint
  ): F[Either[ServiceError, Option[StoredResponse]]] =
    orderedGroupIds.traverse(groupId => runtime(groupId).readState.map(_.responses.get((requestContext.tenantId, requestContext.requestId)))).map { stored =>
      stored.flatten.toList match
        case Nil => Right(None)
        case head :: tail if (head :: tail).forall(_.fingerprint == fingerprint) => Right(Some(head))
        case _ => Left(ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters"))
    }

  private def validateGlobalActiveLeaseQuota(tenantId: TenantId): F[Option[ServiceError]] =
    clock.now.flatMap { now =>
      orderedGroupIds
        .traverse(groupId => runtime(groupId).readState.map(_.leaseState.activeLeaseCount(tenantId, now)))
        .map(_.sum)
        .map(activeCount => quotas.validateActiveLeases(quotas.policyFor(tenantId), tenantId, activeCount).leftMap(ServiceError.QuotaExceeded.apply).swap.toOption)
    }

private enum DuplicateCheck[+A]:
  case Replay(result: Either[ServiceError, A])
  case Reject(error: ServiceError)
