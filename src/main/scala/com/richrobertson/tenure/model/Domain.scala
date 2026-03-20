package com.richrobertson.tenure.model

import java.time.Instant
import java.util.UUID

final case class TenantId(value: String) extends AnyVal
final case class ResourceId(value: String) extends AnyVal
final case class ClientId(value: String) extends AnyVal
final case class LeaseId(value: UUID) extends AnyVal
final case class RequestId(value: String) extends AnyVal

final case class ResourceKey(tenantId: TenantId, resourceId: ResourceId)

enum LeaseStatus:
  case Active, Released, Expired, Absent

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
  def isActiveAt(at: Instant): Boolean =
    status == LeaseStatus.Active && expiresAt.isAfter(at)

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

object LeaseView:
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
