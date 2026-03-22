/**
 * Core domain identifiers and lease views used throughout Tenure.
 *
 * This package is the smallest shared vocabulary in the codebase. Experienced readers can treat it as
 * the reference definition for identifiers, persisted lease state, and externally readable lease state.
 * New readers should start here before diving into the service, state machine, or Raft layers, because
 * nearly every other package is phrased in terms of these types.
 */
package com.richrobertson.tenure.model

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.*
import java.time.Instant
import java.util.UUID

/** Tenant-scoped identifier for all user-visible operations. */
final case class TenantId(value: String) extends AnyVal

/** Circe codecs for [[TenantId]]. */
object TenantId:
  given Codec[TenantId] = Codec.from(Decoder.decodeString.map(TenantId.apply), Encoder.encodeString.contramap(_.value))

/** Resource identifier within one [[TenantId]]. */
final case class ResourceId(value: String) extends AnyVal

/** Circe codecs for [[ResourceId]]. */
object ResourceId:
  given Codec[ResourceId] = Codec.from(Decoder.decodeString.map(ResourceId.apply), Encoder.encodeString.contramap(_.value))

/** Holder or client identifier recorded on a lease. */
final case class ClientId(value: String) extends AnyVal

/** Circe codecs for [[ClientId]]. */
object ClientId:
  given Codec[ClientId] = Codec.from(Decoder.decodeString.map(ClientId.apply), Encoder.encodeString.contramap(_.value))

/**
 * Stable identifier for one granted lease epoch.
 *
 * A lease may be renewed many times without changing its [[LeaseId]], but a release followed by reacquire
 * will produce a new identifier.
 */
final case class LeaseId(value: UUID) extends AnyVal

/** Constructors and codecs for [[LeaseId]]. */
object LeaseId:
  /** Creates a fresh random lease identifier for a newly admitted acquire. */
  def random(): LeaseId = LeaseId(UUID.randomUUID())
  given Codec[LeaseId] = Codec.from(Decoder.decodeUUID.map(LeaseId.apply), Encoder.encodeUUID.contramap(_.value))

/**
 * Idempotency key supplied by clients for mutating operations.
 *
 * Tenure deduplicates request IDs per tenant, so callers should reuse the same value when retrying the
 * same logical acquire, renew, or release.
 */
final case class RequestId(value: String) extends AnyVal

/** Circe codecs for [[RequestId]]. */
object RequestId:
  given Codec[RequestId] = Codec.from(Decoder.decodeString.map(RequestId.apply), Encoder.encodeString.contramap(_.value))

/**
 * Canonical resource identity.
 *
 * This is the stable key used by routing, state machine storage, and service APIs. The architecture treats
 * `(tenant_id, resource_id)` as the durable identity so future sharding can change placement without
 * redesigning the client contract.
 */
final case class ResourceKey(tenantId: TenantId, resourceId: ResourceId)

/** Circe codecs for [[ResourceKey]]. */
object ResourceKey:
  given Codec[ResourceKey] = deriveCodec

/**
 * User-visible lease status.
 *
 * `Active`, `Released`, and `Expired` describe known lease epochs. `Absent` is a synthetic read-model value
 * used when no lease exists for a resource.
 */
enum LeaseStatus:
  case Active, Released, Expired, Absent

/** Circe codecs for [[LeaseStatus]]. */
object LeaseStatus:
  given Codec[LeaseStatus] = Codec.from(
    Decoder.decodeString.map(str => LeaseStatus.valueOf(str)),
    Encoder.encodeString.contramap(_.toString)
  )

/**
 * Authoritative materialized lease record stored in state.
 *
 * This is the state-machine-centric form of a lease. It keeps the full internal history needed for renewal,
 * release, fencing-token progression, and time-based status rendering.
 *
 * Use [[LeaseView]] when returning information to callers. Use [[LeaseRecord]] when evaluating authoritative
 * transitions inside the state machine or service layer.
 */
final case class LeaseRecord(
    leaseId: LeaseId,
    resourceKey: ResourceKey,
    holderId: ClientId,
    status: LeaseStatus,
    acquiredAt: Instant,
    expiresAt: Instant,
    releasedAt: Option[Instant],
    lastRenewedAt: Option[Instant],
    fencingToken: Long,
    version: Long
):
  /** Returns `true` when the lease is still active at the provided instant. */
  def isActiveAt(at: Instant): Boolean =
    status == LeaseStatus.Active && expiresAt.isAfter(at)

/** Circe codecs for [[LeaseRecord]]. */
object LeaseRecord:
  given Codec[LeaseRecord] = deriveCodec

/**
 * Read-model projection of a lease.
 *
 * This form is intentionally friendlier to API callers than [[LeaseRecord]]. Missing values are represented
 * explicitly so callers can distinguish "no lease exists" from "a lease exists but is released or expired".
 *
 * Example:
 * {{{
 * val view = LeaseView.absent(ResourceKey(TenantId("acme"), ResourceId("scheduler")))
 * assert(view.status == LeaseStatus.Absent)
 * }}}
 */
final case class LeaseView(
    leaseId: Option[LeaseId],
    tenantId: TenantId,
    resourceId: ResourceId,
    holderId: Option[ClientId],
    status: LeaseStatus,
    acquiredAt: Option[Instant],
    expiresAt: Option[Instant],
    releasedAt: Option[Instant],
    lastRenewedAt: Option[Instant],
    fencingToken: Long,
    version: Long
)

/** Constructors and codecs for [[LeaseView]]. */
object LeaseView:
  given Codec[LeaseView] = deriveCodec

  /**
   * Renders a [[LeaseRecord]] into a caller-facing [[LeaseView]] at a specific point in time.
   *
   * The supplied `at` instant matters because an internally stored `Active` record may already be expired
   * from the caller's perspective when rendered.
   */
  def fromRecord(record: LeaseRecord, at: Instant): LeaseView =
    val status =
      if record.status == LeaseStatus.Released then LeaseStatus.Released
      else if record.isActiveAt(at) then LeaseStatus.Active
      else LeaseStatus.Expired

    LeaseView(
      leaseId = Some(record.leaseId),
      tenantId = record.resourceKey.tenantId,
      resourceId = record.resourceKey.resourceId,
      holderId = Some(record.holderId),
      status = status,
      acquiredAt = Some(record.acquiredAt),
      expiresAt = Some(record.expiresAt),
      releasedAt = record.releasedAt,
      lastRenewedAt = record.lastRenewedAt,
      fencingToken = record.fencingToken,
      version = record.version
    )

  /**
   * Creates an explicit "no lease exists" view for a resource.
   *
   * This is used by read paths that want to stay tenant/resource-addressable even when no lease has ever
   * been granted.
   */
  def absent(resourceKey: ResourceKey): LeaseView =
    LeaseView(
      leaseId = None,
      tenantId = resourceKey.tenantId,
      resourceId = resourceKey.resourceId,
      holderId = None,
      status = LeaseStatus.Absent,
      acquiredAt = None,
      expiresAt = None,
      releasedAt = None,
      lastRenewedAt = None,
      fencingToken = 0L,
      version = 0L
    )
