package com.richrobertson.tenure.service

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Ref
import cats.syntax.all.*
import com.richrobertson.tenure.model.*
import com.richrobertson.tenure.statemachine.{LeaseState, LeaseStateMachine}
import com.richrobertson.tenure.time.Clock

trait LeaseService[F[_]]:
  def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]]
  def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]]
  def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]]
  def getLease(tenantId: TenantId, resourceId: ResourceId): F[GetLeaseResult]

final case class AcquireRequest(tenantId: String, resourceId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class RenewRequest(tenantId: String, resourceId: String, leaseId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class ReleaseRequest(tenantId: String, resourceId: String, leaseId: String, holderId: String, requestId: String)

final case class AcquireResult(lease: LeaseView, created: Boolean)
final case class RenewResult(lease: LeaseView, renewed: Boolean)
final case class ReleaseResult(lease: LeaseView, released: Boolean)
final case class GetLeaseResult(found: Boolean, lease: LeaseView)

enum ServiceError derives CanEqual:
  case InvalidRequest(message: String)
  case AlreadyHeld(message: String)
  case LeaseExpired(message: String)
  case LeaseMismatch(message: String)
  case NotFound(message: String)

private enum RequestOperation:
  case Acquire, Renew, Release

private final case class StoredResponse(operation: RequestOperation, resourceKey: ResourceKey, result: Either[ServiceError, Any])
private final case class ServiceState(leaseState: LeaseState, responses: Map[(TenantId, RequestId), StoredResponse])

object LeaseService:
  def inMemory[F[_]: Concurrent](clock: Clock[F]): F[LeaseService[F]] =
    Ref.of[F, ServiceState](ServiceState(LeaseState.empty, Map.empty)).map(ref => InMemoryLeaseService[F](ref, clock))

private final case class InMemoryLeaseService[F[_]: Concurrent](stateRef: Ref[F, ServiceState], clock: Clock[F]) extends LeaseService[F]:

  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request).fold(_.asLeft[AcquireResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, RequestOperation.Acquire) {
        Concurrent[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          clock.now.flatMap { now =>
            stateRef.modify { current =>
              LeaseStateMachine.transition(current.leaseState, Acquire(valid.resourceKey, valid.holderId, valid.ttlSeconds, nextLeaseId), now) match
                case Left(error) => current -> Left(mapError(error))
                case Right((nextLeaseState, LeaseResult.Acquired(record))) =>
                  val response = AcquireResult(LeaseView.fromRecord(record, now), created = true)
                  current.copy(leaseState = nextLeaseState) -> Right(response)
                case Right(_) => current -> Left(ServiceError.InvalidRequest("unexpected acquire result"))
            }
          }
        }
      }
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request).fold(_.asLeft[RenewResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, RequestOperation.Renew) {
        clock.now.flatMap { now =>
          stateRef.modify { current =>
            LeaseStateMachine.transition(current.leaseState, Renew(valid.resourceKey, valid.leaseId, valid.holderId, valid.ttlSeconds.getOrElse(0L)), now) match
              case Left(error) => current -> Left(mapError(error))
              case Right((nextLeaseState, LeaseResult.Renewed(record))) =>
                val response = RenewResult(LeaseView.fromRecord(record, now), renewed = true)
                current.copy(leaseState = nextLeaseState) -> Right(response)
              case Right(_) => current -> Left(ServiceError.InvalidRequest("unexpected renew result"))
          }
        }
      }
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request).fold(_.asLeft[ReleaseResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, RequestOperation.Release) {
        clock.now.flatMap { now =>
          stateRef.modify { current =>
            LeaseStateMachine.transition(current.leaseState, Release(valid.resourceKey, valid.leaseId, valid.holderId), now) match
              case Left(error) => current -> Left(mapError(error))
              case Right((nextLeaseState, LeaseResult.Released(record))) =>
                val response = ReleaseResult(LeaseView.fromRecord(record, now), released = true)
                current.copy(leaseState = nextLeaseState) -> Right(response)
              case Right(_) => current -> Left(ServiceError.InvalidRequest("unexpected release result"))
          }
        }
      }
    )

  override def getLease(tenantId: TenantId, resourceId: ResourceId): F[GetLeaseResult] =
    clock.now.flatMap { now =>
      stateRef.get.map { current =>
        val resourceKey = ResourceKey(tenantId, resourceId)
        current.leaseState.get(resourceKey) match
          case Some(record) => GetLeaseResult(found = true, lease = LeaseView.fromRecord(record, now))
          case None         => GetLeaseResult(found = false, lease = LeaseView.absent(resourceKey))
      }
    }

  private final case class RequestContext(tenantId: TenantId, requestId: RequestId, resourceKey: ResourceKey)
  private final case class ValidAcquire(resourceKey: ResourceKey, holderId: ClientId, ttlSeconds: Long, requestContext: RequestContext)
  private final case class ValidMutating(resourceKey: ResourceKey, leaseId: LeaseId, holderId: ClientId, ttlSeconds: Option[Long], requestContext: RequestContext)

  private def validateAcquire(request: AcquireRequest): Either[ServiceError, ValidAcquire] =
    for
      requestContext <- validateRequestContext(request.tenantId, request.resourceId, request.requestId)
      holder <- nonEmpty("holder_id", request.holderId).map(ClientId.apply)
      ttl <- validateTtl(request.ttlSeconds)
    yield ValidAcquire(requestContext.resourceKey, holder, ttl, requestContext)

  private def validateRenew(request: RenewRequest): Either[ServiceError, ValidMutating] =
    validateMutatingRequest(request.tenantId, request.resourceId, request.leaseId, request.holderId, Some(request.ttlSeconds), request.requestId)

  private def validateRelease(request: ReleaseRequest): Either[ServiceError, ValidMutating] =
    validateMutatingRequest(request.tenantId, request.resourceId, request.leaseId, request.holderId, None, request.requestId)

  private def validateMutatingRequest(
      tenantId: String,
      resourceId: String,
      leaseId: String,
      holderId: String,
      ttlSeconds: Option[Long],
      requestId: String
  ): Either[ServiceError, ValidMutating] =
    for
      requestContext <- validateRequestContext(tenantId, resourceId, requestId)
      parsedLeaseId <- parseLeaseId(leaseId).leftMap(ServiceError.InvalidRequest.apply)
      holder <- nonEmpty("holder_id", holderId).map(ClientId.apply)
      validatedTtl <- ttlSeconds.traverse(validateTtl)
    yield ValidMutating(requestContext.resourceKey, parsedLeaseId, holder, validatedTtl, requestContext)

  private def validateRequestContext(tenantId: String, resourceId: String, requestId: String): Either[ServiceError, RequestContext] =
    for
      tenant <- nonEmpty("tenant_id", tenantId).map(TenantId.apply)
      resource <- nonEmpty("resource_id", resourceId).map(ResourceId.apply)
      request <- nonEmpty("request_id", requestId).map(RequestId.apply)
      resourceKey = ResourceKey(tenant, resource)
    yield RequestContext(tenant, request, resourceKey)

  private def nonEmpty(field: String, value: String): Either[ServiceError, String] =
    Either.cond(value.trim.nonEmpty, value.trim, ServiceError.InvalidRequest(s"$field must be non-empty"))

  private def validateTtl(ttlSeconds: Long): Either[ServiceError, Long] =
    Either.cond(ttlSeconds > 0, ttlSeconds, ServiceError.InvalidRequest("ttl_seconds must be positive"))

  private def parseLeaseId(value: String): Either[String, LeaseId] =
    Either.catchNonFatal(java.util.UUID.fromString(value.trim)).leftMap(_ => "lease_id must be a valid UUID").map(LeaseId.apply)

  private def handleWithIdempotency[A](requestContext: RequestContext, operation: RequestOperation)(compute: F[Either[ServiceError, A]]): F[Either[ServiceError, A]] =
    stateRef.get.flatMap { current =>
      current.responses.get((requestContext.tenantId, requestContext.requestId)) match
        case Some(stored) if stored.operation == operation && stored.resourceKey == requestContext.resourceKey =>
          stored.result.asInstanceOf[Either[ServiceError, A]].pure[F]
        case Some(_) =>
          ServiceError.InvalidRequest("request_id cannot be reused for a different operation or resource").asLeft[A].pure[F]
        case None =>
          compute.flatMap { result =>
            stateRef.update(currentState => remember(currentState, requestContext, operation, currentState.leaseState, result.asInstanceOf[Either[ServiceError, Any]])) *> result.pure[F]
          }
    }

  private def remember(
      current: ServiceState,
      requestContext: RequestContext,
      operation: RequestOperation,
      nextLeaseState: LeaseState,
      result: Either[ServiceError, Any]
  ): ServiceState =
    current.copy(
      leaseState = nextLeaseState,
      responses = current.responses.updated((requestContext.tenantId, requestContext.requestId), StoredResponse(operation, requestContext.resourceKey, result))
    )

  private def mapError(error: LeaseError): ServiceError =
    error match
      case LeaseError.AlreadyHeld(activeLease) => ServiceError.AlreadyHeld(LeaseError.AlreadyHeld(activeLease).message)
      case expired: LeaseError.LeaseExpired    => ServiceError.LeaseExpired(expired.message)
      case mismatch: LeaseError.LeaseMismatch  => ServiceError.LeaseMismatch(mismatch.message)
      case missing: LeaseError.NotFound        => ServiceError.NotFound(missing.message)
      case validation: LeaseError.Validation   => ServiceError.InvalidRequest(validation.message)
