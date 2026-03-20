package com.richrobertson.tenure.service

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Ref
import cats.syntax.all.*
import com.richrobertson.tenure.model.*
import com.richrobertson.tenure.statemachine.{LeaseState, LeaseStateMachine}
import com.richrobertson.tenure.time.Clock

trait LeaseService[F[_]]:
  def acquire(request: AcquireRequest): F[Either[ServiceError, LeaseView]]
  def renew(request: RenewRequest): F[Either[ServiceError, LeaseView]]
  def release(request: ReleaseRequest): F[Either[ServiceError, LeaseView]]
  def getLease(tenantId: TenantId, resourceId: ResourceId): F[Option[LeaseView]]

final case class AcquireRequest(tenantId: String, resourceId: String, holderId: String, ttlSeconds: Long)
final case class RenewRequest(tenantId: String, resourceId: String, leaseId: String, holderId: String, ttlSeconds: Long)
final case class ReleaseRequest(tenantId: String, resourceId: String, leaseId: String, holderId: String)

enum ServiceError derives CanEqual:
  case InvalidRequest(message: String)
  case AlreadyHeld(message: String)
  case LeaseExpired(message: String)
  case LeaseMismatch(message: String)
  case NotFound(message: String)

object LeaseService:
  def inMemory[F[_]: Concurrent](clock: Clock[F]): F[LeaseService[F]] =
    Ref.of[F, LeaseState](LeaseState.empty).map(ref => InMemoryLeaseService[F](ref, clock))

private final case class InMemoryLeaseService[F[_]: Concurrent](stateRef: Ref[F, LeaseState], clock: Clock[F]) extends LeaseService[F]:

  override def acquire(request: AcquireRequest): F[Either[ServiceError, LeaseView]] =
    validateAcquire(request) match
      case Left(error) => error.asLeft[LeaseView].pure[F]
      case Right(valid) =>
        Concurrent[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          clock.now.flatMap { now =>
            stateRef.modify { current =>
              LeaseStateMachine.transition(current, Acquire(valid.resourceKey, valid.holderId, valid.ttlSeconds, nextLeaseId), now) match
                case Left(error) => current -> Left(mapError(error))
                case Right((nextState, LeaseResult.Acquired(record))) => nextState -> Right(LeaseView.fromRecord(record, now))
                case Right(_) => current -> Left(ServiceError.InvalidRequest("unexpected acquire result"))
            }
          }
        }

  override def renew(request: RenewRequest): F[Either[ServiceError, LeaseView]] =
    parseLeaseId(request.leaseId).leftMap(ServiceError.InvalidRequest.apply).fold(
      _.asLeft[LeaseView].pure[F],
      leaseId =>
        validateMutatingRequest(request.tenantId, request.resourceId, request.holderId, Some(request.ttlSeconds)).fold(
          _.asLeft[LeaseView].pure[F],
          valid =>
            clock.now.flatMap { now =>
              stateRef.modify { current =>
                LeaseStateMachine.transition(current, Renew(valid.resourceKey, leaseId, valid.holderId, request.ttlSeconds), now) match
                  case Left(error) => current -> Left(mapError(error))
                  case Right((nextState, LeaseResult.Renewed(record))) => nextState -> Right(LeaseView.fromRecord(record, now))
                  case Right(_) => current -> Left(ServiceError.InvalidRequest("unexpected renew result"))
              }
            }
        )
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, LeaseView]] =
    parseLeaseId(request.leaseId).leftMap(ServiceError.InvalidRequest.apply).fold(
      _.asLeft[LeaseView].pure[F],
      leaseId =>
        validateMutatingRequest(request.tenantId, request.resourceId, request.holderId, None).fold(
          _.asLeft[LeaseView].pure[F],
          valid =>
            clock.now.flatMap { now =>
              stateRef.modify { current =>
                LeaseStateMachine.transition(current, Release(valid.resourceKey, leaseId, valid.holderId), now) match
                  case Left(error) => current -> Left(mapError(error))
                  case Right((nextState, LeaseResult.Released(record))) => nextState -> Right(LeaseView.fromRecord(record, now))
                  case Right(_) => current -> Left(ServiceError.InvalidRequest("unexpected release result"))
              }
            }
        )
    )

  override def getLease(tenantId: TenantId, resourceId: ResourceId): F[Option[LeaseView]] =
    clock.now.flatMap { now =>
      stateRef.get.map(_.get(ResourceKey(tenantId, resourceId)).map(LeaseView.fromRecord(_, now)))
    }

  private final case class ValidRequest(resourceKey: ResourceKey, holderId: ClientId, ttlSeconds: Long)

  private def validateAcquire(request: AcquireRequest): Either[ServiceError, ValidRequest] =
    validateMutatingRequest(request.tenantId, request.resourceId, request.holderId, Some(request.ttlSeconds))

  private def validateMutatingRequest(
      tenantId: String,
      resourceId: String,
      holderId: String,
      ttlSeconds: Option[Long]
  ): Either[ServiceError, ValidRequest] =
    for
      tenant <- nonEmpty("tenant_id", tenantId).map(TenantId.apply)
      resource <- nonEmpty("resource_id", resourceId).map(ResourceId.apply)
      holder <- nonEmpty("holder_id", holderId).map(ClientId.apply)
      ttl <- ttlSeconds match
        case Some(value) => validateTtl(value)
        case None        => Right(0L)
    yield ValidRequest(ResourceKey(tenant, resource), holder, ttl)

  private def nonEmpty(field: String, value: String): Either[ServiceError, String] =
    Either.cond(value.trim.nonEmpty, value.trim, ServiceError.InvalidRequest(s"$field must be non-empty"))

  private def validateTtl(ttlSeconds: Long): Either[ServiceError, Long] =
    Either.cond(ttlSeconds > 0, ttlSeconds, ServiceError.InvalidRequest("ttl_seconds must be positive"))

  private def parseLeaseId(value: String): Either[String, LeaseId] =
    Either
      .catchNonFatal(java.util.UUID.fromString(value.trim))
      .leftMap(_ => "lease_id must be a valid UUID")
      .map(LeaseId.apply)

  private def mapError(error: LeaseError): ServiceError =
    error match
      case LeaseError.AlreadyHeld(activeLease) => ServiceError.AlreadyHeld(LeaseError.AlreadyHeld(activeLease).message)
      case expired: LeaseError.LeaseExpired    => ServiceError.LeaseExpired(expired.message)
      case mismatch: LeaseError.LeaseMismatch  => ServiceError.LeaseMismatch(mismatch.message)
      case missing: LeaseError.NotFound        => ServiceError.NotFound(missing.message)
      case validation: LeaseError.Validation   => ServiceError.InvalidRequest(validation.message)
