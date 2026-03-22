/**
 * Pure lease commands, errors, and results used by the domain/state-machine layers.
 *
 * These types intentionally avoid HTTP, Raft transport, and effect concerns. They are the smallest command
 * vocabulary that describes what should happen to one resource.
 */
package com.richrobertson.tenure.model

/** Base trait for lease lifecycle commands applied to a single [[ResourceKey]]. */
sealed trait LeaseCommand:
  /** Resource affected by this command. */
  def resourceKey: ResourceKey

/** Acquire a new lease epoch for a resource. */
final case class Acquire(resourceKey: ResourceKey, holderId: ClientId, ttlSeconds: Long, leaseId: LeaseId) extends LeaseCommand

/** Renew an existing lease epoch without changing its [[LeaseId]]. */
final case class Renew(resourceKey: ResourceKey, leaseId: LeaseId, holderId: ClientId, ttlSeconds: Long) extends LeaseCommand

/** Release an active lease voluntarily. */
final case class Release(resourceKey: ResourceKey, leaseId: LeaseId, holderId: ClientId) extends LeaseCommand

/** Domain-level lease command failure. */
sealed trait LeaseError derives CanEqual:
  /** Human-readable explanation of the failure. */
  def message: String

/** Concrete [[LeaseError]] cases returned by validation or state-machine application. */
object LeaseError:
  /** The resource already has an active lease owned by someone else. */
  final case class AlreadyHeld(activeLease: LeaseRecord) extends LeaseError:
    override def message: String = s"resource ${activeLease.resourceKey} is already held by ${activeLease.holderId.value}"

  /** A lease targeted by renew or release is no longer active. */
  final case class LeaseExpired(resourceKey: ResourceKey) extends LeaseError:
    override def message: String = s"lease for resource $resourceKey is no longer active (expired or released)"

  /** The supplied holder or lease identifier does not match the stored record. */
  final case class LeaseMismatch(resourceKey: ResourceKey) extends LeaseError:
    override def message: String = s"lease holder or lease id did not match resource $resourceKey"

  /** No lease record exists for the resource. */
  final case class NotFound(resourceKey: ResourceKey) extends LeaseError:
    override def message: String = s"no lease found for resource $resourceKey"

  /** Generic validation failure for malformed or unsupported input. */
  final case class Validation(message: String) extends LeaseError

/** Successful domain-level lease command result. */
sealed trait LeaseResult

/** Concrete [[LeaseResult]] shapes for each lifecycle transition. */
object LeaseResult:
  /** Acquire succeeded and returned the created authoritative record. */
  final case class Acquired(record: LeaseRecord) extends LeaseResult

  /** Renew succeeded and returned the updated authoritative record. */
  final case class Renewed(record: LeaseRecord) extends LeaseResult

  /** Release succeeded and returned the resulting authoritative record. */
  final case class Released(record: LeaseRecord) extends LeaseResult
