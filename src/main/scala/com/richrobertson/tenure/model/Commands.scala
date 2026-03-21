package com.richrobertson.tenure.model

sealed trait LeaseCommand:
  def resourceKey: ResourceKey

final case class Acquire(resourceKey: ResourceKey, holderId: ClientId, ttlSeconds: Long, leaseId: LeaseId) extends LeaseCommand
final case class Renew(resourceKey: ResourceKey, leaseId: LeaseId, holderId: ClientId, ttlSeconds: Long) extends LeaseCommand
final case class Release(resourceKey: ResourceKey, leaseId: LeaseId, holderId: ClientId) extends LeaseCommand

sealed trait LeaseError derives CanEqual:
  def message: String

object LeaseError:
  final case class AlreadyHeld(activeLease: LeaseRecord) extends LeaseError:
    override def message: String = s"resource ${activeLease.resourceKey} is already held by ${activeLease.holderId.value}"

  final case class LeaseExpired(resourceKey: ResourceKey) extends LeaseError:
    override def message: String = s"lease for resource $resourceKey is no longer active (expired or released)"

  final case class LeaseMismatch(resourceKey: ResourceKey) extends LeaseError:
    override def message: String = s"lease holder or lease id did not match resource $resourceKey"

  final case class NotFound(resourceKey: ResourceKey) extends LeaseError:
    override def message: String = s"no lease found for resource $resourceKey"

  final case class Validation(message: String) extends LeaseError

sealed trait LeaseResult
object LeaseResult:
  final case class Acquired(record: LeaseRecord) extends LeaseResult
  final case class Renewed(record: LeaseRecord) extends LeaseResult
  final case class Released(record: LeaseRecord) extends LeaseResult
