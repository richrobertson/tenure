/**
 * Lease-service request/response contracts plus concrete service implementations.
 *
 * This package is the main application layer of Tenure. It validates caller input, enforces authorization
 * and quota policy, manages idempotency, and delegates authoritative mutation either to an in-memory state
 * machine or to a replicated Raft-backed runtime.
 */
package com.richrobertson.tenure.service

import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.richrobertson.tenure.auth.{Authorization, Principal}
import com.richrobertson.tenure.group.GroupRuntime
import com.richrobertson.tenure.model.*
import com.richrobertson.tenure.observability.{LogEvent, Observability}
import com.richrobertson.tenure.quota.{TenantQuotaPolicy, TenantQuotaRegistry}
import com.richrobertson.tenure.raft.RaftNode
import com.richrobertson.tenure.routing.Router
import com.richrobertson.tenure.statemachine.{LeaseState, LeaseStateMachine}
import com.richrobertson.tenure.time.Clock
import java.time.Instant
import java.util.UUID

/** Effectful service interface for lease lifecycle and read operations. */
trait LeaseService[F[_]]:
  /** Attempts to acquire a lease for one `(tenant_id, resource_id)` pair. */
  def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]]
  /** Attempts to renew an existing lease epoch. */
  def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]]
  /** Attempts to release an active lease. */
  def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]]
  /** Returns the current lease view for one resource. */
  def getLease(request: GetLeaseRequest): F[Either[ServiceError, GetLeaseResult]]
  /** Lists lease views for one tenant. */
  def listLeases(request: ListLeasesRequest): F[Either[ServiceError, ListLeasesResult]]

/** Runtime bundle pairing a [[LeaseService]] with a state-read hook. */
final case class LeaseServiceRuntime[F[_]](service: LeaseService[F], readState: F[ServiceState])

/** Acquire request at the service boundary. */
final case class AcquireRequest(principal: Principal, tenantId: String, resourceId: String, holderId: String, ttlSeconds: Long, requestId: String)
/** Renew request at the service boundary. */
final case class RenewRequest(principal: Principal, tenantId: String, resourceId: String, leaseId: String, holderId: String, ttlSeconds: Long, requestId: String)
/** Release request at the service boundary. */
final case class ReleaseRequest(principal: Principal, tenantId: String, resourceId: String, leaseId: String, holderId: String, requestId: String)
/** Read request for one lease. */
final case class GetLeaseRequest(principal: Principal, tenantId: String, resourceId: String)
/** Tenant-scoped read request for all leases. */
final case class ListLeasesRequest(principal: Principal, tenantId: String)

/** Successful acquire result. */
final case class AcquireResult(lease: LeaseView, created: Boolean)
/** Successful renew result. */
final case class RenewResult(lease: LeaseView, renewed: Boolean)
/** Successful release result. */
final case class ReleaseResult(lease: LeaseView, released: Boolean)
/** Successful single-resource read result. */
final case class GetLeaseResult(found: Boolean, lease: LeaseView)
/** Successful tenant-scoped read result. */
final case class ListLeasesResult(leases: List[LeaseView])

/** Service-layer error surface returned to API and evaluator callers. */
sealed trait ServiceError derives CanEqual

/** Concrete [[ServiceError]] cases. */
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

/** Operation kind encoded into request-id fingerprints. */
enum RequestOperation:
  case Acquire, Renew, Release

/** Canonical identity of a mutating request for idempotency comparisons. */
final case class RequestFingerprint(
    operation: RequestOperation,
    resourceKey: ResourceKey,
    holderId: ClientId,
    leaseId: Option[LeaseId],
    ttlSeconds: Option[Long]
) derives CanEqual

/** Stored replayable result for a previously applied mutating request. */
sealed trait StoredResult derives CanEqual

/** Concrete [[StoredResult]] wrappers for each write operation. */
object StoredResult:
  final case class Acquire(value: Either[ServiceError, AcquireResult]) extends StoredResult
  final case class Renew(value: Either[ServiceError, RenewResult]) extends StoredResult
  final case class Release(value: Either[ServiceError, ReleaseResult]) extends StoredResult

/** Stored request fingerprint plus its replayable result. */
final case class StoredResponse(fingerprint: RequestFingerprint, result: StoredResult) derives CanEqual
/** Complete service-layer authoritative state. */
final case class ServiceState(leaseState: LeaseState, responses: Map[(TenantId, RequestId), StoredResponse]) derives CanEqual

/** Constructors for [[ServiceState]]. */
object ServiceState:
  /** Empty service state with no leases and no idempotency history. */
  val empty: ServiceState = ServiceState(LeaseState.empty, Map.empty)

/** Command shape replicated through Raft and applied to [[ServiceState]]. */
sealed trait ReplicatedCommand derives CanEqual:
  /** Tenant/request/resource context that ties the command back to the caller. */
  def requestContext: RequestContext
  /** Authoritative apply/admission instant used for lease timing. */
  def appliedAt: Instant

/** Shared mutating request context. */
final case class RequestContext(tenantId: TenantId, requestId: RequestId, resourceKey: ResourceKey) derives CanEqual

/** Replicated acquire command. */
final case class AcquireCommand(
    requestContext: RequestContext,
    holderId: ClientId,
    ttlSeconds: Long,
    leaseId: LeaseId,
    quotaPolicy: TenantQuotaPolicy,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual:
  /** Idempotency fingerprint for this acquire. */
  def fingerprint: RequestFingerprint =
    RequestFingerprint(RequestOperation.Acquire, requestContext.resourceKey, holderId, None, Some(ttlSeconds))

/** Replicated renew command. */
final case class RenewCommand(
    requestContext: RequestContext,
    leaseId: LeaseId,
    holderId: ClientId,
    ttlSeconds: Long,
    quotaPolicy: TenantQuotaPolicy,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual:
  /** Idempotency fingerprint for this renew. */
  def fingerprint: RequestFingerprint =
    RequestFingerprint(RequestOperation.Renew, requestContext.resourceKey, holderId, Some(leaseId), Some(ttlSeconds))

/** Replicated release command. */
final case class ReleaseCommand(
    requestContext: RequestContext,
    leaseId: LeaseId,
    holderId: ClientId,
    appliedAt: Instant
) extends ReplicatedCommand derives CanEqual:
  /** Idempotency fingerprint for this release. */
  def fingerprint: RequestFingerprint =
    RequestFingerprint(RequestOperation.Release, requestContext.resourceKey, holderId, Some(leaseId), None)

/**
 * Applies replicated commands to [[ServiceState]].
 *
 * This is the service-layer materializer used both during live command application and during recovery
 * replay from persisted log/snapshot state.
 */
object LeaseMaterializer:
  /** Applies one replicated command and returns the next [[ServiceState]]. */
  def applyCommand(state: ServiceState, command: ReplicatedCommand): ServiceState =
    command match
      case command: AcquireCommand =>
        handleCommand(state, command.requestContext, command.fingerprint, StoredResult.Acquire.apply) {
          TenantQuotaRegistry.validateTtl(command.quotaPolicy, command.requestContext.tenantId, command.ttlSeconds).leftMap(ServiceError.QuotaExceeded.apply).flatMap { _ =>
            val activeCount = state.leaseState.activeLeaseCount(command.requestContext.tenantId, command.appliedAt)
            TenantQuotaRegistry.validateActiveLeases(command.quotaPolicy, command.requestContext.tenantId, activeCount).leftMap(ServiceError.QuotaExceeded.apply).flatMap { _ =>
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
          TenantQuotaRegistry.validateTtl(command.quotaPolicy, command.requestContext.tenantId, command.ttlSeconds).leftMap(ServiceError.QuotaExceeded.apply).flatMap { _ =>
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

  /** Extracts a replayable acquire result from a stored response value. */
  def replayAcquire(result: StoredResult): Option[Either[ServiceError, AcquireResult]] = result match
    case StoredResult.Acquire(value) => Some(value)
    case _                           => None

  /** Extracts a replayable renew result from a stored response value. */
  def replayRenew(result: StoredResult): Option[Either[ServiceError, RenewResult]] = result match
    case StoredResult.Renew(value) => Some(value)
    case _                         => None

  /** Extracts a replayable release result from a stored response value. */
  def replayRelease(result: StoredResult): Option[Either[ServiceError, ReleaseResult]] = result match
    case StoredResult.Release(value) => Some(value)
    case _                           => None

  /** Maps pure domain/state-machine errors into service-layer errors. */
  def mapError(error: LeaseError): ServiceError =
    error match
      case LeaseError.AlreadyHeld(activeLease) => ServiceError.AlreadyHeld(LeaseError.AlreadyHeld(activeLease).message)
      case expired: LeaseError.LeaseExpired    => ServiceError.LeaseExpired(expired.message)
      case mismatch: LeaseError.LeaseMismatch  => ServiceError.LeaseMismatch(mismatch.message)
      case missing: LeaseError.NotFound        => ServiceError.NotFound(missing.message)
      case validation: LeaseError.Validation   => ServiceError.InvalidRequest(validation.message)

  private def withResponse(state: ServiceState, requestContext: RequestContext, fingerprint: RequestFingerprint, result: StoredResult): ServiceState =
    state.copy(responses = state.responses.updated((requestContext.tenantId, requestContext.requestId), StoredResponse(fingerprint, result)))

/** Factory methods for in-memory, replicated, and routed lease services. */
object LeaseService:
  /** Creates an in-memory service with default quotas, auth, and no-op observability. */
  def inMemory[F[_]: Async](clock: Clock[F]): F[LeaseService[F]] =
    inMemoryRuntime(clock, TenantQuotaRegistry.default, Authorization.perTenant, Observability.noop[F]).map(_.service)

  /** Creates an in-memory service with caller-supplied quotas. */
  def inMemory[F[_]: Async](clock: Clock[F], quotas: TenantQuotaRegistry): F[LeaseService[F]] =
    inMemoryRuntime(clock, quotas, Authorization.perTenant, Observability.noop[F]).map(_.service)

  /** Creates an in-memory service with caller-supplied quotas and authorization. */
  def inMemory[F[_]: Async](clock: Clock[F], quotas: TenantQuotaRegistry, authorization: Authorization): F[LeaseService[F]] =
    inMemoryRuntime(clock, quotas, authorization, Observability.noop[F]).map(_.service)

  /** Creates an in-memory service with full caller control over supporting dependencies. */
  def inMemory[F[_]: Async](
      clock: Clock[F],
      quotas: TenantQuotaRegistry,
      authorization: Authorization,
      observability: Observability[F]
  ): F[LeaseService[F]] =
    inMemoryRuntime(clock, quotas, authorization, observability).map(_.service)

  /** Creates an in-memory runtime bundle including `readState`. */
  def inMemoryRuntime[F[_]: Async](
      clock: Clock[F],
      quotas: TenantQuotaRegistry,
      authorization: Authorization,
      observability: Observability[F]
  ): F[LeaseServiceRuntime[F]] =
    for
      stateRef <- cats.effect.Ref.of[F, ServiceState](ServiceState.empty)
      mutationLock <- Mutex[F]
      service = LocalLeaseService[F](stateRef, mutationLock, clock, quotas, authorization, observability)
    yield LeaseServiceRuntime(service, stateRef.get)

  /** Creates a replicated service backed by a [[RaftNode]]. */
  def replicated[F[_]: Async](raftNode: RaftNode[F], clock: Clock[F]): LeaseService[F] =
    replicatedRuntime(raftNode, clock, TenantQuotaRegistry.default, Authorization.perTenant, Observability.noop[F]).service

  /** Creates a replicated service with caller-supplied observability. */
  def replicated[F[_]: Async](raftNode: RaftNode[F], clock: Clock[F], observability: Observability[F]): LeaseService[F] =
    replicatedRuntime(raftNode, clock, TenantQuotaRegistry.default, Authorization.perTenant, observability).service

  /** Creates a replicated service with full caller control over supporting dependencies. */
  def replicated[F[_]: Async](
      raftNode: RaftNode[F],
      clock: Clock[F],
      quotas: TenantQuotaRegistry,
      authorization: Authorization,
      observability: Observability[F]
  ): LeaseService[F] =
    replicatedRuntime(raftNode, clock, quotas, authorization, observability).service

  /** Creates a replicated runtime bundle including `readState`. */
  def replicatedRuntime[F[_]: Async](
      raftNode: RaftNode[F],
      clock: Clock[F],
      quotas: TenantQuotaRegistry,
      authorization: Authorization,
      observability: Observability[F]
  ): LeaseServiceRuntime[F] =
    LeaseServiceRuntime(ReplicatedLeaseService[F](raftNode, clock, quotas, authorization, observability), raftNode.readState)

  /** Creates a routed service with default quotas, auth, and no-op observability. */
  def routed[F[_]: Async](router: Router, groups: List[GroupRuntime[F]], clock: Clock[F]): F[LeaseService[F]] =
    routed(router, groups, clock, TenantQuotaRegistry.default, Authorization.perTenant, Observability.noop[F])

  /** Creates a routed service with caller-supplied observability. */
  def routed[F[_]: Async](router: Router, groups: List[GroupRuntime[F]], clock: Clock[F], observability: Observability[F]): F[LeaseService[F]] =
    routed(router, groups, clock, TenantQuotaRegistry.default, Authorization.perTenant, observability)

  /**
   * Creates a routed service over one or more logical groups.
   *
   * Even in local multi-group mode, the caller-facing API stays keyed by tenant/resource rather than by
   * physical group placement.
   */
  def routed[F[_]: Async](
      router: Router,
      groups: List[GroupRuntime[F]],
      clock: Clock[F],
      quotas: TenantQuotaRegistry,
      authorization: Authorization,
      observability: Observability[F]
  ): F[LeaseService[F]] =
    val duplicateGroupIds = groups.groupBy(_.groupId).collect { case (groupId, entries) if entries.size > 1 => groupId }.toList.sortBy(_.value)
    if duplicateGroupIds.nonEmpty then
      Async[F].raiseError(new IllegalArgumentException(s"duplicate group ids when constructing LeaseService.routed: ${duplicateGroupIds.map(_.value).mkString(", ")}"))
    else
      TenantAdmissionLocks.create[F].map { admissionLocks =>
        RoutedLeaseService(router, groups.map(group => group.groupId -> group).toMap, clock, quotas, authorization, observability, admissionLocks)
      }

/** Shared request-validation helpers used by local and replicated services. */
private[service] trait ValidationSupport:
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

/** Common observability mapping for service operations. */
object ServiceObservability:
  private def errorCode(error: ServiceError): String =
    error match
      case _: ServiceError.InvalidRequest => "INVALID_ARGUMENT"
      case _: ServiceError.Unauthorized => "UNAUTHORIZED"
      case _: ServiceError.Forbidden => "FORBIDDEN"
      case _: ServiceError.AlreadyHeld => "ALREADY_HELD"
      case _: ServiceError.LeaseExpired => "LEASE_EXPIRED"
      case _: ServiceError.LeaseMismatch => "LEASE_MISMATCH"
      case _: ServiceError.NotFound => "NOT_FOUND"
      case _: ServiceError.QuotaExceeded => "QUOTA_EXCEEDED"
      case _: ServiceError.NotLeader => "NOT_LEADER"

  private def operationKind(operation: String): String =
    operation match
      case "get" | "list" => "get"
      case _ => "write"

  def recordResult[F[_]: Async](observability: Observability[F], clock: Clock[F], nodeId: String, operation: String, requestContext: Option[RequestContext], result: Either[ServiceError, Unit], dedupe: Boolean = false): F[Unit] =
    clock.now.flatMap { now =>
      val labels = Map("node_id" -> nodeId, "operation" -> operation, "result" -> result.fold(_ => "error", _ => "success"))
      val ctxFields = requestContext.fold(Map.empty[String, String])(ctx => Map("tenant_id" -> ctx.tenantId.value, "resource_id" -> ctx.resourceKey.resourceId.value, "request_id" -> ctx.requestId.value))
      val base = observability.incrementCounter(if dedupe then "duplicate_requests_total" else "service_requests_total", labels)
      result match
        case Right(_) =>
          base *> observability.log(LogEvent(now.toEpochMilli, "INFO", "lease.request.result", s"$operation completed", nodeId = Some(nodeId), tenantId = requestContext.map(_.tenantId.value), resourceId = requestContext.map(_.resourceKey.resourceId.value), requestId = requestContext.map(_.requestId.value), result = Some(if dedupe then "duplicate_replay" else "success"), fields = ctxFields))
        case Left(error) =>
          val code = errorCode(error)
          val extra = error match
            case _: ServiceError.NotLeader => observability.incrementCounter("not_leader_responses_total", Map("node_id" -> nodeId, "operation_kind" -> operationKind(operation), "operation" -> operation))
            case _: ServiceError.QuotaExceeded => observability.incrementCounter("quota_rejections_total", Map("node_id" -> nodeId, "operation" -> operation))
            case _: ServiceError.Forbidden | _: ServiceError.Unauthorized => observability.incrementCounter("auth_denials_total", Map("node_id" -> nodeId, "operation" -> operation))
            case _ => Async[F].unit
          base *> extra *> observability.log(LogEvent(now.toEpochMilli, "WARN", "lease.request.result", s"$operation failed", nodeId = Some(nodeId), tenantId = requestContext.map(_.tenantId.value), resourceId = requestContext.map(_.resourceKey.resourceId.value), requestId = requestContext.map(_.requestId.value), result = Some(if dedupe then "duplicate_replay" else "error"), errorCode = Some(code), fields = ctxFields))
      }

private final case class LocalLeaseService[F[_]: Async](
    stateRef: cats.effect.Ref[F, ServiceState],
    mutationLock: Mutex[F],
    clock: Clock[F],
    quotas: TenantQuotaRegistry,
    authorization: Authorization,
    observability: Observability[F]
) extends LeaseService[F]
    with ValidationSupport:

  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, "local", "acquire", None, Left(err).map(_ => ())) *> err.asLeft[AcquireResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, valid.fingerprint, "acquire")(LeaseMaterializer.replayAcquire) {
        Async[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          clock.now.map { now =>
            val command = AcquireCommand(valid.requestContext, valid.holderId, valid.ttlSeconds, nextLeaseId, quotas.policyFor(valid.requestContext.tenantId), now)
            stateRef.modify { current =>
              val next = LeaseMaterializer.applyCommand(current, command)
              next -> LeaseMaterializer.replayAcquire(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
            }
          }.flatten.flatTap(result => ServiceObservability.recordResult(observability, clock, "local", "acquire", Some(valid.requestContext), result.map(_ => ())))
        }
      }
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, "local", "renew", None, Left(err).map(_ => ())) *> err.asLeft[RenewResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, valid.fingerprint, "renew")(LeaseMaterializer.replayRenew) {
        clock.now.map { now =>
          val command = RenewCommand(valid.requestContext, valid.leaseId, valid.holderId, valid.ttlSeconds, quotas.policyFor(valid.requestContext.tenantId), now)
          stateRef.modify { current =>
            val next = LeaseMaterializer.applyCommand(current, command)
            next -> LeaseMaterializer.replayRenew(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }.flatten.flatTap(result => ServiceObservability.recordResult(observability, clock, "local", "renew", Some(valid.requestContext), result.map(_ => ())))
      }
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, "local", "release", None, Left(err).map(_ => ())) *> err.asLeft[ReleaseResult].pure[F], valid =>
      handleWithIdempotency(valid.requestContext, valid.fingerprint, "release")(LeaseMaterializer.replayRelease) {
        clock.now.map { now =>
          val command = ReleaseCommand(valid.requestContext, valid.leaseId, valid.holderId, now)
          stateRef.modify { current =>
            val next = LeaseMaterializer.applyCommand(current, command)
            next -> LeaseMaterializer.replayRelease(next.responses((valid.requestContext.tenantId, valid.requestContext.requestId)).result).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }
        }.flatten.flatTap(result => ServiceObservability.recordResult(observability, clock, "local", "release", Some(valid.requestContext), result.map(_ => ())))
      }
    )

  override def getLease(request: GetLeaseRequest): F[Either[ServiceError, GetLeaseResult]] =
    validateRead(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, "local", "get", None, Left(err).map(_ => ())) *> err.asLeft[GetLeaseResult].pure[F], valid =>
      clock.now.flatMap { now =>
        stateRef.get.map { current =>
          val lease = current.leaseState.viewAt(valid.resourceKey, now)
          Right(GetLeaseResult(found = lease.status != LeaseStatus.Absent, lease = lease))
        }
      }.flatTap(result => ServiceObservability.recordResult(observability, clock, "local", "get", Some(RequestContext(valid.tenantId, RequestId("get-local"), valid.resourceKey)), result.map(_ => ())))
    )

  override def listLeases(request: ListLeasesRequest): F[Either[ServiceError, ListLeasesResult]] =
    validateList(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, "local", "list", None, Left(err).map(_ => ())) *> err.asLeft[ListLeasesResult].pure[F], tenantId =>
      clock.now.flatMap { now =>
        stateRef.get.map { current =>
          Right(ListLeasesResult(current.leaseState.tenantViewsAt(tenantId, now)))
        }
      }.flatTap(result => ServiceObservability.recordResult(observability, clock, "local", "list", Some(RequestContext(tenantId, RequestId("list-local"), ResourceKey(tenantId, ResourceId("tenant-scope")))), result.map(_ => ())))
    )

  private def handleWithIdempotency[A](requestContext: RequestContext, fingerprint: RequestFingerprint, operation: String)(
      replay: StoredResult => Option[Either[ServiceError, A]]
  )(
      compute: F[Either[ServiceError, A]]
  ): F[Either[ServiceError, A]] =
    mutationLock.lock.surround {
      stateRef.get.flatMap { current =>
        current.responses.get((requestContext.tenantId, requestContext.requestId)) match
          case Some(stored) if stored.fingerprint == fingerprint =>
            val replayed = replay(stored.result).getOrElse(ServiceError.InvalidRequest("stored request replay type did not match operation").asLeft[A])
            ServiceObservability.recordResult(observability, clock, "local", operation, Some(requestContext), replayed.map(_ => ()), dedupe = true) *> replayed.pure[F]
          case Some(_) =>
            val error = ServiceError.InvalidRequest("request_id cannot be reused for a different operation, resource, or parameters")
            ServiceObservability.recordResult(observability, clock, "local", operation, Some(requestContext), Left(error).map(_ => ())) *> error.asLeft[A].pure[F]
          case None => compute
      }
    }

private final case class ReplicatedLeaseService[F[_]: Async](raftNode: RaftNode[F], clock: Clock[F], quotas: TenantQuotaRegistry, authorization: Authorization, observability: Observability[F])
    extends LeaseService[F]
    with ValidationSupport:
  override def acquire(request: AcquireRequest): F[Either[ServiceError, AcquireResult]] =
    validateAcquire(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "acquire", None, Left(err).map(_ => ())) *> err.asLeft[AcquireResult].pure[F], valid =>
      clock.now.flatMap(now =>
        Async[F].delay(LeaseId.random()).flatMap { nextLeaseId =>
          raftNode.submit(AcquireCommand(valid.requestContext, valid.holderId, valid.ttlSeconds, nextLeaseId, quotas.policyFor(valid.requestContext.tenantId), now)).map {
            case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
            case Right(stored) => LeaseMaterializer.replayAcquire(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
          }.flatTap(result => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "acquire", Some(valid.requestContext), result.map(_ => ())))
        }
      )
    )

  override def renew(request: RenewRequest): F[Either[ServiceError, RenewResult]] =
    validateRenew(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "renew", None, Left(err).map(_ => ())) *> err.asLeft[RenewResult].pure[F], valid =>
      clock.now.flatMap(now =>
        raftNode.submit(RenewCommand(valid.requestContext, valid.leaseId, valid.holderId, valid.ttlSeconds, quotas.policyFor(valid.requestContext.tenantId), now)).map {
          case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
          case Right(stored) => LeaseMaterializer.replayRenew(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
        }.flatTap(result => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "renew", Some(valid.requestContext), result.map(_ => ())))
      )
    )

  override def release(request: ReleaseRequest): F[Either[ServiceError, ReleaseResult]] =
    validateRelease(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "release", None, Left(err).map(_ => ())) *> err.asLeft[ReleaseResult].pure[F], valid =>
      clock.now.flatMap(now =>
        raftNode.submit(ReleaseCommand(valid.requestContext, valid.leaseId, valid.holderId, now)).map {
          case Left(notLeader) => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not the leader", notLeader.leaderHint))
          case Right(stored) => LeaseMaterializer.replayRelease(stored).getOrElse(Left(ServiceError.InvalidRequest("stored request replay type did not match operation")))
        }.flatTap(result => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "release", Some(valid.requestContext), result.map(_ => ())))
      )
    )

  override def getLease(request: GetLeaseRequest): F[Either[ServiceError, GetLeaseResult]] =
    validateRead(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "get", None, Left(err).map(_ => ())) *> err.asLeft[GetLeaseResult].pure[F], valid =>
      raftNode.canServeLeaderReads.flatMap { authoritative =>
        if authoritative then
          clock.now.flatMap { now =>
            raftNode.readState.map { current =>
              val lease = current.leaseState.viewAt(valid.resourceKey, now)
              Right(GetLeaseResult(found = lease.status != LeaseStatus.Absent, lease = lease))
            }
          }.flatTap(result => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "get", Some(RequestContext(valid.tenantId, RequestId("get-read"), valid.resourceKey)), result.map(_ => ())))
        else raftNode.leaderHint.map(hint => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not authoritative for reads", hint))).flatTap(result => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "get", Some(RequestContext(valid.tenantId, RequestId("read-no-request-id"), valid.resourceKey)), result.map(_ => ())))
      }
    )

  override def listLeases(request: ListLeasesRequest): F[Either[ServiceError, ListLeasesResult]] =
    validateList(request, authorization).fold(err => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "list", None, Left(err).map(_ => ())) *> err.asLeft[ListLeasesResult].pure[F], tenantId =>
      raftNode.canServeLeaderReads.flatMap { authoritative =>
        if authoritative then
          clock.now.flatMap { now =>
            raftNode.readState.map { current =>
              Right(ListLeasesResult(current.leaseState.tenantViewsAt(tenantId, now)))
            }
          }.flatTap(result => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "list", Some(RequestContext(tenantId, RequestId("list-read"), ResourceKey(tenantId, ResourceId("tenant-scope")))), result.map(_ => ())))
        else raftNode.leaderHint.map(hint => Left(ServiceError.NotLeader(s"node ${raftNode.nodeId} is not authoritative for reads", hint))).flatTap(result => ServiceObservability.recordResult(observability, clock, raftNode.nodeId, "list", Some(RequestContext(tenantId, RequestId("list-no-request-id"), ResourceKey(tenantId, ResourceId("tenant-scope")))), result.map(_ => ())))
      }
    )
