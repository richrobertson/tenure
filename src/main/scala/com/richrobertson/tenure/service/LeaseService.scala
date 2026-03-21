package com.richrobertson.tenure.service

import cats.effect.kernel.{Async, Concurrent, Ref, Sync}
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.richrobertson.tenure.model.*
import com.richrobertson.tenure.raft.{NodeRole, RaftNode}
import com.richrobertson.tenure.statemachine.{LeaseState, LeaseStateMachine}
import com.richrobertson.tenure.time.Clock
import java.time.Instant
import java.util.UUID

trait LeaseService[F[_]]:
  def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]]
  def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]]
  def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]]
  def getLease(tenantId: TenantId, resourceId: ResourceId): F[Either[ServiceError, GetLeaseResult]]
  def listLeases(tenantId: TenantId): F[Either[ServiceError, ListLeasesResult]]

final case class AcquireRequest(tenantId: String, resourceId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class RenewRequest(tenantId: String, resourceId: String, leaseId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class ReleaseRequest(tenantId: String, resourceId: String, leaseId: String, holderId: String, requestId: String)

final case class AcquireResult(lease: LeaseView, created: Boolean)
final case class RenewResult(lease: LeaseView, renewed: Boolean)
final case class ReleaseResult(lease: LeaseView, released: Boolean)
final case class GetLeaseResult(found: Boolean, lease: LeaseView)
final case class ListLeasesResult(leases: List[LeaseView])

sealed trait ServiceError derives CanEqual
object ServiceError:
  final case class InvalidRequest(message: String) extends ServiceError
  final case class AlreadyHeld(message: String) extends ServiceError
  final case class LeaseExpired(message: String) extends ServiceError
  final case class LeaseMismatch(message: String) extends ServiceError
  final case class NotFound(message: String) extends ServiceError
  final case class NotLeader(message: String, leaderHint: Option[String]) extends ServiceError

enum RequestOperation:
  case Acquire, Renew, Release

sealed trait StoredResult derives CanEqual
object StoredResult:
  final case class Acquire(value: Either[ServiceError, AcquireResult]) extends StoredResult
  final case class Renew(value: Either[ServiceError, RenewResult]) extends StoredResult
  final case class Release(value: Either[ServiceError, ReleaseResult]) extends StoredResult

final case class StoredResponse(operation: RequestOperation, resourceKey: ResourceKey, result: StoredResult)
final case class ServiceState(leaseState: LeaseState, responses: Map[(TenantId, RequestId), StoredResponse])

object ServiceState:
  val empty: ServiceState = ServiceState(LeaseState.empty, Map.empty)

sealed trait ReplicatedCommand derives CanEqual:
  def requestContext: RequestContext
  def appliedAt: Instant

final case class RequestContext(tenantId: TenantId, requestId: RequestId, resourceKey: ResourceKey) derives CanEqual
final case class AcquireCommand(
    requestContext: RequestContext,
    holderId: ClientId,
    ttlSeconds: Long,
    leaseId: LeaseId,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual
final case class RenewCommand(
    requestContext: RequestContext,
    leaseId: LeaseId,
    holderId: ClientId,
    ttlSeconds: Long,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual
final case class ReleaseCommand(
    requestContext: RequestContext,
    leaseId: LeaseId,
    holderId: ClientId,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual

object LeaseMaterializer:
  def applyCommand(state: ServiceState, command: ReplicatedCommand): ServiceState =
    command match
      case command: AcquireCommand =>
        state.responses.get((command.requestContext.tenantId, command.requestContext.requestId)) match
          case Some(existing) if existing.operation == RequestOperation.Acquire && existing.resourceKey == command.requestContext.resourceKey => state
          case Some(_) =>
            val result = StoredResult.Acquire(Left(ServiceError.InvalidRequest("request_id cannot be reused for a different operation or resource")))
            state.copy(responses = state.responses.updated((command.requestContext.tenantId, command.requestContext.requestId), StoredResponse(RequestOperation.Acquire, command.requestContext.resourceKey, result)))
          case None =>
            LeaseStateMachine.transition(
              state.leaseState,
              com.richrobertson.tenure.model.Acquire(command.requestContext.resourceKey, command.holderId, command.ttlSeconds, command.leaseId),
              command.appliedAt
            ) match
              case Left(error) => withResponse(state, command.requestContext, RequestOperation.Acquire, StoredResult.Acquire(Left(mapError(error))))
              case Right((nextLeaseState, LeaseResult.Acquired(record))) =>
                withResponse(
                  state.copy(leaseState = nextLeaseState),
                  command.requestContext,
                  RequestOperation.Acquire,
                  StoredResult.Acquire(Right(AcquireResult(LeaseView.fromRecord(record, command.appliedAt), created = true)))
                )
              case Right(_) => withResponse(state, command.requestContext, RequestOperation.Acquire, StoredResult.Acquire(Left(ServiceError.InvalidRequest("unexpected acquire result"))))

      case command: RenewCommand =>
        state.responses.get((command.requestContext.tenantId, command.requestContext.requestId)) match
          case Some(existing) if existing.operation == RequestOperation.Renew && existing.resourceKey == command.requestContext.resourceKey => state
          case Some(_) =>
            val result = StoredResult.Renew(Left(ServiceError.InvalidRequest("request_id cannot be reused for a different operation or resource")))
            state.copy(responses = state.responses.updated((command.requestContext.tenantId, command.requestContext.requestId), StoredResponse(RequestOperation.Renew, command.requestContext.resourceKey, result)))
          case None =>
            LeaseStateMachine.transition(
              state.leaseState,
              com.richrobertson.tenure.model.Renew(command.requestContext.resourceKey, command.leaseId, command.holderId, command.ttlSeconds),
              command.appliedAt
            ) match
              case Left(error) => withResponse(state, command.requestContext, RequestOperation.Renew, StoredResult.Renew(Left(mapError(error))))
              case Right((nextLeaseState, LeaseResult.Renewed(record))) =>
                withResponse(
                  state.copy(leaseState = nextLeaseState),
                  command.requestContext,
                  RequestOperation.Renew,
                  StoredResult.Renew(Right(RenewResult(LeaseView.fromRecord(record, command.appliedAt), renewed = true)))
                )
              case Right(_) => withResponse(state, command.requestContext, RequestOperation.Renew, StoredResult.Renew(Left(ServiceError.InvalidRequest("unexpected renew result"))))

      case command: ReleaseCommand =>
        state.responses.get((command.requestContext.tenantId, command.requestContext.requestId)) match
          case Some(existing) if existing.operation == RequestOperation.Release && existing.resourceKey == command.requestContext.resourceKey => state
          case Some(_) =>
            val result = StoredResult.Release(Left(ServiceError.InvalidRequest("request_id cannot be reused for a different operation or resource")))
            state.copy(responses = state.responses.updated((command.requestContext.tenantId, command.requestContext.requestId), StoredResponse(RequestOperation.Release, command.requestContext.resourceKey, result)))
          case None =>
            LeaseStateMachine.transition(
              state.leaseState,
              com.richrobertson.tenure.model.Release(command.requestContext.resourceKey, command.leaseId, command.holderId),
              command.appliedAt
            ) match
              case Left(error) => withResponse(state, command.requestContext, RequestOperation.Release, StoredResult.Release(Left(mapError(error))))
              case Right((nextLeaseState, LeaseResult.Released(record))) =>
                withResponse(
                  state.copy(leaseState = nextLeaseState),
                  command.requestContext,
                  RequestOperation.Release,
                  StoredResult.Release(Right(ReleaseResult(LeaseView.fromRecord(record, command.appliedAt), released = true)))
                )
              case Right(_) => withResponse(state, command.requestContext, RequestOperation.Release, StoredResult.Release(Left(ServiceError.InvalidRequest("unexpected release result"))))

  def replayAcquire(result: StoredResult): Option[Either[ServiceError, AcquireResult]] = result match
    case StoredResult.Acquire(value) => Some(value)
    case _                           => None

  def replayRenew(result: StoredResult): Option[Either[ServiceError, RenewResult]] = result match
    case StoredResult.Renew(value) => Some(value)
    case _                         => None

  def replayRelease(result: StoredResult): Option[Either[ServiceError, ReleaseResult]] = result match
    case StoredResult.Release(value) => Some(value)
    case _                           => None

  def mapError(error: LeaseError): ServiceError =
    error match
      case LeaseError.AlreadyHeld(activeLease) => ServiceError.AlreadyHeld(LeaseError.AlreadyHeld(activeLease).message)
      case expired: LeaseError.LeaseExpired    => ServiceError.LeaseExpired(expired.message)
      case mismatch: LeaseError.LeaseMismatch  => ServiceError.LeaseMismatch(mismatch.message)
      case missing: LeaseError.NotFound        => ServiceError.NotFound(missing.message)
      case validation: LeaseError.Validation   => ServiceError.InvalidRequest(validation.message)

  private def withResponse(state: ServiceState, requestContext: RequestContext, operation: RequestOperation, result: StoredResult): ServiceState =
    state.copy(responses = state.responses.updated((requestContext.tenantId, requestContext.requestId), StoredResponse(operation, requestContext.resourceKey, result)))

object LeaseService:
  def inMemory[F[_]: Async](clock: Clock[F]): F[LeaseService[F]] =
    for
      stateRef <- Ref.of[F, ServiceState](ServiceState.empty)
      mutationLock <- Mutex[F]
    yield LocalLeaseService[F](stateRef, mutationLock, clock)

  def replicated[F[_]: Async](raftNode: RaftNode[F], clock: Clock[F]): LeaseService[F] =
    ReplicatedLeaseService[F](raftNode, clock)

private trait ValidationSupport:
  final case class ValidAcquire(resourceKey: ResourceKey, holderId: ClientId, ttlSeconds: Long, requestContext: RequestContext)
  final case class ValidMutating(resourceKey: ResourceKey, leaseId: LeaseId, holderId: ClientId, ttlSeconds: Long, requestContext: RequestContext)

  protected def validateAcquire(request: AcquireRequest): Either[ServiceError, ValidAcquire] =
    for
      requestContext <- validateRequestContext(request.tenantId, request.resourceId, request.requestId)
      holder <- nonEmpty("holder_id", request.holderId).map(ClientId.apply)
      ttl <- validateTtl(request.ttlSeconds)
    yield ValidAcquire(requestContext.resourceKey, holder, ttl, requestContext)

  protected def validateRenew(request: RenewRequest): Either[ServiceError, ValidMutating] =
    validateMutatingRequest(request.tenantId, request.resourceId, request.leaseId, request.holderId, Some(request.ttlSeconds), request.requestId)

  protected def validateRelease(request: ReleaseRequest): Either[ServiceError, ValidMutating] =
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
    yield ValidMutating(requestContext.resourceKey, parsedLeaseId, holder, validatedTtl.getOrElse(0L), requestContext)

  protected def validateRequestContext(tenantId: String, resourceId: String, requestId: String): Either[ServiceError, RequestContext] =
    for
      tenant <- nonEmpty("tenant_id", tenantId).map(TenantId.apply)
      resource <- nonEmpty("resource_id", resourceId).map(ResourceId.apply)
      request <- nonEmpty("request_id", requestId).map(RequestId.apply)
      resourceKey = ResourceKey(tenant, resource)
    yield RequestContext(tenant, request, resourceKey)

  protected def nonEmpty(field: String, value: String): Either[ServiceError, String] =
    Either.cond(value.trim.nonEmpty, value.trim, ServiceError.InvalidRequest(s"$field must be non-empty"))

  protected def validateTtl(ttlSeconds: Long): Either[ServiceError, Long] =
    Either.cond(ttlSeconds > 0, ttlSeconds, ServiceError.InvalidRequest("ttl_seconds must be positive"))

  protected def parseLeaseId(value: String): Either[String, LeaseId] =
    Either.catchNonFatal(UUID.fromString(value.trim)).leftMap(_ => "lease_id must be a valid UUID").map(LeaseId.apply)

private final case class LocalLeaseService[F[_]: Async](stateRef: Ref[F, ServiceState], mutationLock: Mutex[F], clock: Clock[F])
    extends LeaseService[F]
    with ValidationSupport:

  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request).fold(_.asLeft[AcquireResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, RequestOperation.Acquire)(LeaseMaterializer.replayAcquire) {
        Async[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          clock.now.map { now =>
            val command = AcquireCommand(valid.requestContext, valid.holderId, valid.ttlSeconds, nextLeaseId, now)
            stateRef.modify { current =>
              val next = LeaseMaterializer.applyCommand(current, command)
              next -> LeaseMaterializer.replayAcquire(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
            }
          }.flatten
        }
      }
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request).fold(_.asLeft[RenewResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, RequestOperation.Renew)(LeaseMaterializer.replayRenew) {
        clock.now.map { now =>
          val command = RenewCommand(valid.requestContext, valid.leaseId, valid.holderId, valid.ttlSeconds, now)
          stateRef.modify { current =>
            val next = LeaseMaterializer.applyCommand(current, command)
            next -> LeaseMaterializer.replayRenew(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }.flatten
      }
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request).fold(_.asLeft[ReleaseResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, RequestOperation.Release)(LeaseMaterializer.replayRelease) {
        clock.now.map { now =>
          val command = ReleaseCommand(valid.requestContext, valid.leaseId, valid.holderId, now)
          stateRef.modify { current =>
            val next = LeaseMaterializer.applyCommand(current, command)
            next -> LeaseMaterializer.replayRelease(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }.flatten
      }
    )

  override def getLease(tenantId: TenantId, resourceId: ResourceId): F[Either[ServiceError, GetLeaseResult]] =
    clock.now.flatMap { now =>
      stateRef.get.map { current =>
        val resourceKey = ResourceKey(tenantId, resourceId)
        current.leaseState.get(resourceKey) match
          case Some(record) => Right(GetLeaseResult(found = true, lease = LeaseView.fromRecord(record, now)))
          case None         => Right(GetLeaseResult(found = false, lease = LeaseView.absent(resourceKey)))
      }
    }

  override def listLeases(tenantId: TenantId): F[Either[ServiceError, ListLeasesResult]] =
    clock.now.flatMap { now =>
      stateRef.get.map { current =>
        Right(ListLeasesResult(current.leaseState.leases.values.toList.filter(_.resourceKey.tenantId == tenantId).sortBy(_.resourceKey.resourceId.value).map(LeaseView.fromRecord(_, now))))
      }
    }

  private def handleWithIdempotency[A](requestContext: RequestContext, operation: RequestOperation)(
      replay: StoredResult => Option[Either[ServiceError, A]]
  )(
      compute: F[Either[ServiceError, A]]
  ): F[Either[ServiceError, A]] =
    mutationLock.lock.surround {
      stateRef.get.flatMap { current =>
        current.responses.get((requestContext.tenantId, requestContext.requestId)) match
          case Some(stored) if stored.operation == operation && stored.resourceKey == requestContext.resourceKey =>
            replay(stored.result).getOrElse(ServiceError.InvalidRequest("stored request replay type did not match operation").asLeft[A]).pure[F]
          case Some(_) =>
            ServiceError.InvalidRequest("request_id cannot be reused for a different operation or resource").asLeft[A].pure[F]
          case None => compute
      }
    }

private final case class ReplicatedLeaseService[F[_]: Async](raftNode: RaftNode[F], clock: Clock[F]) extends LeaseService[F] with ValidationSupport:
  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request).fold(_.asLeft[AcquireResult].pure[F], valid =>
      clock.now.flatMap(now =>
        Async[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          raftNode.submit(AcquireCommand(valid.requestContext, valid.holderId, valid.ttlSeconds, nextLeaseId, now)).map {
            case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
            case Right(stored) => LeaseMaterializer.replayAcquire(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }
      )
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request).fold(_.asLeft[RenewResult].pure[F], valid =>
      clock.now.flatMap(now =>
        raftNode.submit(RenewCommand(valid.requestContext, valid.leaseId, valid.holderId, valid.ttlSeconds, now)).map {
          case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
          case Right(stored) => LeaseMaterializer.replayRenew(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
        }
      )
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request).fold(_.asLeft[ReleaseResult].pure[F], valid =>
      clock.now.flatMap(now =>
        raftNode.submit(ReleaseCommand(valid.requestContext, valid.leaseId, valid.holderId, now)).map {
          case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
          case Right(stored) => LeaseMaterializer.replayRelease(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
        }
      )
    )

  override def getLease(tenantId: TenantId, resourceId: ResourceId): F[Either[ServiceError, GetLeaseResult]] =
    raftNode.canServeLeaderReads.flatMap { authoritative =>
      if authoritative then
        clock.now.flatMap { now =>
          raftNode.readState.map { current =>
            val resourceKey = ResourceKey(tenantId, resourceId)
            current.leaseState.get(resourceKey) match
              case Some(record) => Right(GetLeaseResult(found = true, lease = LeaseView.fromRecord(record, now)))
              case None         => Right(GetLeaseResult(found = false, lease = LeaseView.absent(resourceKey)))
          }
        }
      else raftNode.leaderHint.map(hint => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not authoritative for reads", hint)))
    }

  override def listLeases(tenantId: TenantId): F[Either[ServiceError, ListLeasesResult]] =
    raftNode.canServeLeaderReads.flatMap { authoritative =>
      if authoritative then
        clock.now.flatMap { now =>
          raftNode.readState.map { current =>
            Right(ListLeasesResult(current.leaseState.leases.values.toList.filter(_.resourceKey.tenantId == tenantId).sortBy(_.resourceKey.resourceId.value).map(LeaseView.fromRecord(_, now))))
          }
        }
      else raftNode.leaderHint.map(hint => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not authoritative for reads", hint)))
    }
