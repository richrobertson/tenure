package com.richrobertson.tenure.model

import java.time.Instant
import java.util.UUID

final case class TenantId(value: String) extends AnyVal
final case class ResourceId(value: String) extends AnyVal
final case class ClientId(value: String) extends AnyVal
final case class LeaseId(value: UUID) extends AnyVal

final case class ResourceKey(tenantId: TenantId, resourceId: ResourceId)

enum LeaseStatus:
  case Active, Released, Expired

final case class LeaseRecord(
    leaseId: LeaseId,
    resourceKey: ResourceKey,
    holderId: ClientId,
    status: LeaseStatus,
    acquiredAt: Instant,
    expiresAt: Instant,
    releasedAt: Option[Instant],
    lastRenewedAt: Option[Instant]
):
  def isActiveAt(at: Instant): Boolean =
    status == LeaseStatus.Active && expiresAt.isAfter(at)

final case class LeaseView(
    leaseId: LeaseId,
    tenantId: TenantId,
    resourceId: ResourceId,
    holderId: ClientId,
    status: LeaseStatus,
    acquiredAt: Instant,
    expiresAt: Instant,
    releasedAt: Option[Instant],
    lastRenewedAt: Option[Instant]
)

object LeaseView:
  def fromRecord(record: LeaseRecord, at: Instant): LeaseView =
    val status =
      if record.status == LeaseStatus.Released then LeaseStatus.Released
      else if record.isActiveAt(at) then LeaseStatus.Active
      else LeaseStatus.Expired

    LeaseView(
      leaseId = record.leaseId,
      tenantId = record.resourceKey.tenantId,
      resourceId = record.resourceKey.resourceId,
      holderId = record.holderId,
      status = status,
      acquiredAt = record.acquiredAt,
      expiresAt = record.expiresAt,
      releasedAt = record.releasedAt,
      lastRenewedAt = record.lastRenewedAt
    )
