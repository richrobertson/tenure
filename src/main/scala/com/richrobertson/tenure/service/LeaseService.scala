package com.richrobertson.tenure.service

import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.richrobertson.tenure.auth.{Authorization, Principal}
import com.richrobertson.tenure.model.*
import com.richrobertson.tenure.quota.{TenantQuotaPolicy, TenantQuotaRegistry}
import com.richrobertson.tenure.raft.RaftNode
import com.richrobertson.tenure.statemachine.{LeaseState, LeaseStateMachine}
import com.richrobertson.tenure.time.Clock
import java.time.Instant
import java.util.UUID

trait LeaseService[F[_]]:
  def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]]
  def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]]
  def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]]
  def getLease(request: GetLeaseRequest): F[Either[ServiceError, GetLeaseResult]]
  def listLeases(request: ListLeasesRequest): F[Either[ServiceError, ListLeasesResult]]

final case class AcquireRequest(principal: Principal, tenantId: String, resourceId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class RenewRequest(principal: Principal, tenantId: String, resourceId: String, leaseId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class ReleaseRequest(principal: Principal, tenantId: String, resourceId: String, leaseId: String, holderId: String, requestId: String)
final case class GetLeaseRequest(principal: Principal, tenantId: String, resourceId: String)
final case class ListLeasesRequest(principal: Principal, tenantId: String)

final case class AcquireResult(lease: LeaseView, created: Boolean)
final case class RenewResult(lease: LeaseView, renewed: Boolean)
final case class ReleaseResult(lease: LeaseView, released: Boolean)
final case class GetLeaseResult(found: Boolean, lease: LeaseView)
final case class ListLeasesResult(leases: List[LeaseView])

sealed trait ServiceError derives CanEqual
object ServiceError:
  final case class InvalidRequest(message: String) extends ServiceError
  final case class Unauthorized(message: String) extends ServiceError
  final case class Forbidden(message: String) extends ServiceError
  final case class AlreadyHeld(message: String) extends ServiceError
  final case class LeaseExpired(message: String) extends ServiceError
  final case class LeaseMismatch(message: String) extends ServiceError
  final case class NotFound(message: String) extends ServiceError
  final case class QuotaExceeded(message: String) extends ServiceError
  final case class NotLeader(message: String, leaderHint: Option[String]) extends ServiceError

enum RequestOperation:
  case Acquire, Renew, Release

final case class RequestFingerprint(
    operation: RequestOperation,
    resourceKey: ResourceKey,
    holderId: ClientId,
    leaseId: Option[LeaseId],
    ttlSeconds: Option[Long]
) derives CanEqual

sealed trait StoredResult derives CanEqual
object StoredResult:
  final case class Acquire(value: Either[ServiceError, AcquireResult]) extends StoredResult
  final case class Renew(value: Either[ServiceError, RenewResult]) extends StoredResult
  final case class Release(value: Either[ServiceError, ReleaseResult]) extends StoredResult

final case class StoredResponse(fingerprint: RequestFingerprint, result: StoredResult) derives CanEqual
final case class ServiceState(leaseState: LeaseState, responses: Map[(TenantId, RequestId), StoredResponse]) derives CanEqual

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
    quotaPolicy: TenantQuotaPolicy,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual:
  def fingerprint: RequestFingerprint =
    RequestFingerprint(RequestOperation.Acquire, requestContext.resourceKey, holderId, None, Some(ttlSeconds))

final case class RenewCommand(
    requestContext: RequestContext,
    leaseId: LeaseId,
    holderId: ClientId,
    ttlSeconds: Long,
    quotaPolicy: TenantQuotaPolicy,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual:
  def fingerprint: RequestFingerprint =
    RequestFingerprint(RequestOperation.Renew, requestContext.resourceKey, holderId, Some(leaseId), Some(ttlSeconds))

final case class ReleaseCommand(
    requestContext: RequestContext,
    leaseId: LeaseId,
    holderId: ClientId,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual:
  def fingerprint: RequestFingerprint =
    RequestFingerprint(RequestOperation.Release, requestContext.resourceKey, holderId, Some(leaseId), None)

object LeaseMaterializer:
  def applyCommand(state: ServiceState, command: ReplicatedCommand): ServiceState =
    command match
      case command: AcquireCommand =>
        handleCommand(state, command.requestContext, command.fingerprint, StoredResult.Acquire.apply) {
          TenantQuotaRegistry.default.validateTtl(command.quotaPolicy, command.requestContext.tenantId, command.ttlSeconds).leftMap(ServiceError.QuotaExceeded.apply).flatMap { _ =>
            val activeCount = state.leaseState.activeLeaseCount(command.requestContext.tenantId, command.appliedAt)
            TenantQuotaRegistry.default.validateActiveLeases(command.quotaPolicy, command.requestContext.tenantId, activeCount).leftMap(ServiceError.QuotaExceeded.apply).flatMap { _ =>
              LeaseStateMachine.transition(
                state.leaseState,
                com.richrobertson.tenure.model.Acquire(command.requestContext.resourceKey, command.holderId, command.ttlSeconds, command.leaseId),
                command.appliedAt
              ) match
                case Left(error) => Left(mapError(error))
                case Right((nextLeaseState, LeaseResult.Acquired(record))) =>
                  Right(state.copy(leaseState = nextLeaseState) -> AcquireResult(LeaseView.fromRecord(record, command.appliedAt), created = true))
                case Right(_) => Left(ServiceError.InvalidRequest("unexpected acquire result"))
            }
          }
        }

      case command: RenewCommand =>
        handleCommand(state, command.requestContext, command.fingerprint, StoredResult.Renew.apply) {
          TenantQuotaRegistry.default.validateTtl(command.quotaPolicy, command.requestContext.tenantId, command.ttlSeconds).leftMap(ServiceError.QuotaExceeded.apply).flatMap { _ =>
            LeaseStateMachine.transition(
              state.leaseState,
              com.richrobertson.tenure.model.Renew(command.requestContext.resourceKey, command.leaseId, command.holderId, command.ttlSeconds),
              command.appliedAt
            ) match
              case Left(error) => Left(mapError(error))
              case Right((nextLeaseState, LeaseResult.Renewed(record))) =>
                Right(state.copy(leaseState = nextLeaseState) -> RenewResult(LeaseView.fromRecord(record, command.appliedAt), renewed = true))
              case Right(_) => Left(ServiceError.InvalidRequest("unexpected renew result"))
          }
        }

      case command: ReleaseCommand =>
        handleCommand(state, command.requestContext, command.fingerprint, StoredResult.Release.apply) {
          LeaseStateMachine.transition(
            state.leaseState,
            com.richrobertson.tenure.model.Release(command.requestContext.resourceKey, command.leaseId, command.holderId),
            command.appliedAt
          ) match
            case Left(error) => Left(mapError(error))
            case Right((nextLeaseState, LeaseResult.Released(record))) =>
              Right(state.copy(leaseState = nextLeaseState) -> ReleaseResult(LeaseView.fromRecord(record, command.appliedAt), released = true))
            case Right(_) => Left(ServiceError.InvalidRequest("unexpected release result"))
        }

  private def handleCommand[A](
      state: ServiceState,
      requestContext: RequestContext,
      fingerprint: RequestFingerprint,
      wrap: Either[ServiceError, A] => StoredResult
  )(
      compute: => Either[ServiceError, (ServiceState, A)]
  ): ServiceState =
    state.responses.get((requestContext.tenantId, requestContext.requestId)) match
      case Some(existing) if existing.fingerprint == fingerprint => state
      case Some(_) =>
        state
      case None =>
        compute match
          case Left(error) => withResponse(state, requestContext, fingerprint, wrap(Left(error)))
          case Right((nextState, value)) => withResponse(nextState, requestContext, fingerprint, wrap(Right(value)))

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

  private def withResponse(state: ServiceState, requestContext: RequestContext, fingerprint: RequestFingerprint, result: StoredResult): ServiceState =
    state.copy(responses = state.responses.updated((requestContext.tenantId, requestContext.requestId), StoredResponse(fingerprint, result)))

object LeaseService:
  def inMemory[F[_]: Async](
      clock: Clock[F],
      quotas: TenantQuotaRegistry = TenantQuotaRegistry.default,
      authorization: Authorization = Authorization.perTenant
  ): F[LeaseService[F]] =
    for
      stateRef <- cats.effect.Ref.of[F, ServiceState](ServiceState.empty)
      mutationLock <- Mutex[F]
    yield LocalLeaseService[F](stateRef, mutationLock, clock, quotas, authorization)

  def replicated[F[_]: Async](
      raftNode: RaftNode[F],
      clock: Clock[F],
      quotas: TenantQuotaRegistry = TenantQuotaRegistry.default,
      authorization: Authorization = Authorization.perTenant
  ): LeaseService[F] =
    ReplicatedLeaseService[F](raftNode, clock, quotas, authorization)

private trait ValidationSupport:
  final case class ValidAcquire(resourceKey: ResourceKey, holderId: ClientId, ttlSeconds: Long, requestContext: RequestContext, fingerprint: RequestFingerprint)
  final case class ValidMutating(resourceKey: ResourceKey, leaseId: LeaseId, holderId: ClientId, ttlSeconds: Long, requestContext: RequestContext, fingerprint: RequestFingerprint)
  final case class ValidRead(resourceKey: ResourceKey, tenantId: TenantId)

  protected def validateAcquire(request: AcquireRequest, authorization: Authorization): Either[ServiceError, ValidAcquire] =
    for
      requestContext <- validateRequestContext(request.principal, request.tenantId, request.resourceId, request.requestId, authorization)
      holder <- nonEmpty("holder_id", request.holderId).map(ClientId.apply)
      ttl <- validateTtl(request.ttlSeconds)
      fingerprint = RequestFingerprint(RequestOperation.Acquire, requestContext.resourceKey, holder, None, Some(ttl))
    yield ValidAcquire(requestContext.resourceKey, holder, ttl, requestContext, fingerprint)

  protected def validateRenew(request: RenewRequest, authorization: Authorization): Either[ServiceError, ValidMutating] =
    validateMutatingRequest(request.principal, request.tenantId, request.resourceId, request.leaseId, request.holderId, Some(request.ttlSeconds), request.requestId, RequestOperation.Renew, authorization)

  protected def validateRelease(request: ReleaseRequest, authorization: Authorization): Either[ServiceError, ValidMutating] =
    validateMutatingRequest(request.principal, request.tenantId, request.resourceId, request.leaseId, request.holderId, None, request.requestId, RequestOperation.Release, authorization)

  protected def validateRead(request: GetLeaseRequest, authorization: Authorization): Either[ServiceError, ValidRead] =
    validateReadRequest(request.principal, request.tenantId, request.resourceId, authorization)

  protected def validateList(request: ListLeasesRequest, authorization: Authorization): Either[ServiceError, TenantId] =
    validateReadRequest(request.principal, request.tenantId, resourceId = "tenant-scope", authorization).map(_.tenantId)

  private def validateReadRequest(principal: Principal, tenantId: String, resourceId: String, authorization: Authorization): Either[ServiceError, ValidRead] =
    for
      tenant <- nonEmpty("tenant_id", tenantId).map(TenantId.apply)
      resource <- nonEmpty("resource_id", resourceId).map(ResourceId.apply)
      _ <- authorize(principal, tenant, authorization)
    yield ValidRead(ResourceKey(tenant, resource), tenant)

  private def validateMutatingRequest(
      principal: Principal,
      tenantId: String,
      resourceId: String,
      leaseId: String,
      holderId: String,
      ttlSeconds: Option[Long],
      requestId: String,
      operation: RequestOperation,
      authorization: Authorization
  ): Either[ServiceError, ValidMutating] =
    for
      requestContext <- validateRequestContext(principal, tenantId, resourceId, requestId, authorization)
      parsedLeaseId <- parseLeaseId(leaseId).leftMap(ServiceError.InvalidRequest.apply)
      holder <- nonEmpty("holder_id", holderId).map(ClientId.apply)
      validatedTtl <- ttlSeconds.traverse(validateTtl)
      fingerprint = RequestFingerprint(operation, requestContext.resourceKey, holder, Some(parsedLeaseId), validatedTtl)
    yield ValidMutating(requestContext.resourceKey, parsedLeaseId, holder, validatedTtl.getOrElse(0L), requestContext, fingerprint)

  protected def validateRequestContext(principal: Principal, tenantId: String, resourceId: String, requestId: String, authorization: Authorization): Either[ServiceError, RequestContext] =
    for
      tenant <- nonEmpty("tenant_id", tenantId).map(TenantId.apply)
      resource <- nonEmpty("resource_id", resourceId).map(ResourceId.apply)
      request <- nonEmpty("request_id", requestId).map(RequestId.apply)
      _ <- authorize(principal, tenant, authorization)
      resourceKey = ResourceKey(tenant, resource)
    yield RequestContext(tenant, request, resourceKey)

  protected def authorize(principal: Principal, tenantId: TenantId, authorization: Authorization): Either[ServiceError, Unit] =
    authorization.authorize(principal, tenantId).leftMap {
      case Authorization.Decision.Unauthorized(message) => ServiceError.Unauthorized(message)
      case Authorization.Decision.Forbidden(message)    => ServiceError.Forbidden(message)
    }

  protected def nonEmpty(field: String, value: String): Either[ServiceError, String] =
    Either.cond(value.trim.nonEmpty, value.trim, ServiceError.InvalidRequest(s"$field must be non-empty"))

  protected def validateTtl(ttlSeconds: Long): Either[ServiceError, Long] =
    Either.cond(ttlSeconds > 0, ttlSeconds, ServiceError.InvalidRequest("ttl_seconds must be positive"))

  protected def parseLeaseId(value: String): Either[String, LeaseId] =
    Either.catchNonFatal(UUID.fromString(value.trim)).leftMap(_ => "lease_id must be a valid UUID").map(LeaseId.apply)

private final case class LocalLeaseService[F[_]: Async](
    stateRef: cats.effect.Ref[F, ServiceState],
    mutationLock: Mutex[F],
    clock: Clock[F],
    quotas: TenantQuotaRegistry,
    authorization: Authorization
) extends LeaseService[F]
    with ValidationSupport:

  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request, authorization).fold(_.asLeft[AcquireResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, valid.fingerprint)(LeaseMaterializer.replayAcquire) {
        Async[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          clock.now.map { now =>
            val command = AcquireCommand(valid.requestContext, valid.holderId, valid.ttlSeconds, nextLeaseId, quotas.policyFor(valid.requestContext.tenantId), now)
            stateRef.modify { current =>
              val next = LeaseMaterializer.applyCommand(current, command)
              next -> LeaseMaterializer.replayAcquire(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
            }
          }.flatten
        }
      }
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request, authorization).fold(_.asLeft[RenewResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, valid.fingerprint)(LeaseMaterializer.replayRenew) {
        clock.now.map { now =>
          val command = RenewCommand(valid.requestContext, valid.leaseId, valid.holderId, valid.ttlSeconds, quotas.policyFor(valid.requestContext.tenantId), now)
          stateRef.modify { current =>
            val next = LeaseMaterializer.applyCommand(current, command)
            next -> LeaseMaterializer.replayRenew(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }.flatten
      }
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request, authorization).fold(_.asLeft[ReleaseResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, valid.fingerprint)(LeaseMaterializer.replayRelease) {
        clock.now.map { now =>
          val command = ReleaseCommand(valid.requestContext, valid.leaseId, valid.holderId, now)
          stateRef.modify { current =>
            val next = LeaseMaterializer.applyCommand(current, command)
            next -> LeaseMaterializer.replayRelease(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }.flatten
      }
    )

  override def getLease(request: GetLeaseRequest): F[Either[ServiceError, GetLeaseResult]] =
    validateRead(request, authorization).fold(_.asLeft[GetLeaseResult].pure[F], valid =>
      clock.now.flatMap { now =>
        stateRef.get.map { current =>
          val lease = current.leaseState.viewAt(valid.resourceKey, now)
          Right(GetLeaseResult(found = lease.status != LeaseStatus.Absent, lease = lease))
        }
      }
    )

  override def listLeases(request: ListLeasesRequest): F[Either[ServiceError, ListLeasesResult]] =
    validateList(request, authorization).fold(_.asLeft[ListLeasesResult].pure[F], tenantId =>
      clock.now.flatMap { now =>
        stateRef.get.map { current =>
          Right(ListLeasesResult(current.leaseState.tenantViewsAt(tenantId, now)))
        }
      }
    )

  private def handleWithIdempotency[A](requestContext: RequestContext, fingerprint: RequestFingerprint)(
      replay: StoredResult => Option[Either[ServiceError, A]]
  )(
      compute: F[Either[ServiceError, A]]
  ): F[Either[ServiceError, A]] =
    mutationLock.lock.surround {
      stateRef.get.flatMap { current =>
        current.responses.get((requestContext.tenantId, requestContext.requestId)) match
          case Some(stored) if stored.fingerprint == fingerprint =>
            replay(stored.result).getOrElse(ServiceError.InvalidRequest("stored request replay type did not match operation").asLeft[A]).pure[F]
          case Some(_) =>
            ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters").asLeft[A].pure[F]
          case None => compute
      }
    }

private final case class ReplicatedLeaseService[F[_]: Async](raftNode: RaftNode[F], clock: Clock[F], quotas: TenantQuotaRegistry, authorization: Authorization)
    extends LeaseService[F]
    with ValidationSupport:
  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request, authorization).fold(_.asLeft[AcquireResult].pure[F], valid =>
      clock.now.flatMap(now =>
        Async[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          raftNode.submit(AcquireCommand(valid.requestContext, valid.holderId, valid.ttlSeconds, nextLeaseId, quotas.policyFor(valid.requestContext.tenantId), now)).map {
            case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
            case Right(stored) => LeaseMaterializer.replayAcquire(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }
      )
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request, authorization).fold(_.asLeft[RenewResult].pure[F], valid =>
      clock.now.flatMap(now =>
        raftNode.submit(RenewCommand(valid.requestContext, valid.leaseId, valid.holderId, valid.ttlSeconds, quotas.policyFor(valid.requestContext.tenantId), now)).map {
          case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
          case Right(stored) => LeaseMaterializer.replayRenew(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
        }
      )
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request, authorization).fold(_.asLeft[ReleaseResult].pure[F], valid =>
      clock.now.flatMap(now =>
        raftNode.submit(ReleaseCommand(valid.requestContext, valid.leaseId, valid.holderId, now)).map {
          case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
          case Right(stored) => LeaseMaterializer.replayRelease(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
        }
      )
    )

  override def getLease(request: GetLeaseRequest): F[Either[ServiceError, GetLeaseResult]] =
    validateRead(request, authorization).fold(_.asLeft[GetLeaseResult].pure[F], valid =>
      raftNode.canServeLeaderReads.flatMap { authoritative =>
        if authoritative then
          clock.now.flatMap { now =>
            raftNode.readState.map { current =>
              val lease = current.leaseState.viewAt(valid.resourceKey, now)
              Right(GetLeaseResult(found = lease.status != LeaseStatus.Absent, lease = lease))
            }
          }
        else raftNode.leaderHint.map(hint => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not authoritative for reads", hint)))
      }
    )

  override def listLeases(request: ListLeasesRequest): F[Either[ServiceError, ListLeasesResult]] =
    validateList(request, authorization).fold(_.asLeft[ListLeasesResult].pure[F], tenantId =>
      raftNode.canServeLeaderReads.flatMap { authoritative =>
        if authoritative then
          clock.now.flatMap { now =>
            raftNode.readState.map { current =>
              Right(ListLeasesResult(current.leaseState.tenantViewsAt(tenantId, now)))
            }
          }
        else raftNode.leaderHint.map(hint => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not authoritative for reads", hint)))
      }
    )
