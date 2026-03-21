package com.richrobertson.tenure.model

import io.circe.Codec
import io.circe.generic.semiauto.*
import java.time.Instant
import java.util.UUID

final case class TenantId(value: String) extends AnyVal
object TenantId:
  given Codec[TenantId] = Codec.from(Codec[String].map(TenantId.apply), Codec[String].contramap(_.value))

final case class ResourceId(value: String) extends AnyVal
object ResourceId:
  given Codec[ResourceId] = Codec.from(Codec[String].map(ResourceId.apply), Codec[String].contramap(_.value))

final case class ClientId(value: String) extends AnyVal
object ClientId:
  given Codec[ClientId] = Codec.from(Codec[String].map(ClientId.apply), Codec[String].contramap(_.value))

final case class LeaseId(value: UUID) extends AnyVal
object LeaseId:
  def random(): LeaseId = LeaseId(UUID.randomUUID())
  given Codec[LeaseId] = Codec.from(Codec[UUID].map(LeaseId.apply), Codec[UUID].contramap(_.value))

final case class RequestId(value: String) extends AnyVal
object RequestId:
  given Codec[RequestId] = Codec.from(Codec[String].map(RequestId.apply), Codec[String].contramap(_.value))

final case class ResourceKey(tenantId: TenantId, resourceId: ResourceId)
object ResourceKey:
  given Codec[ResourceKey] = deriveCodec

enum LeaseStatus:
  case Active, Released, Expired, Absent
object LeaseStatus:
  given Codec[LeaseStatus] = Codec.from(
    Codec[String].map(str => LeaseStatus.valueOf(str)),
    Codec[String].contramap(_.toString)
  )

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
object LeaseRecord:
  given Codec[LeaseRecord] = deriveCodec

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
  given Codec[LeaseView] = deriveCodec

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
