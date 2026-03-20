package com.richrobertson.tenure.statemachine

import com.richrobertson.tenure.model.*
import java.time.Instant

final case class LeaseState(leases: Map[ResourceKey, LeaseRecord]):
  def get(resourceKey: ResourceKey): Option[LeaseRecord] = leases.get(resourceKey)

object LeaseState:
  val empty: LeaseState = LeaseState(Map.empty)

object LeaseStateMachine:
  def transition(state: LeaseState, command: LeaseCommand, now: Instant): Either[LeaseError, (LeaseState, LeaseResult)] =
    command match
      case acquire: Acquire if acquire.ttlSeconds <= 0 => Left(LeaseError.Validation("ttl_seconds must be positive"))
      case renew: Renew if renew.ttlSeconds <= 0       => Left(LeaseError.Validation("ttl_seconds must be positive"))
      case acquire: Acquire                            => handleAcquire(state, acquire, now)
      case renew: Renew                                => handleRenew(state, renew, now)
      case release: Release                            => handleRelease(state, release, now)

  private def handleAcquire(state: LeaseState, command: Acquire, now: Instant): Either[LeaseError, (LeaseState, LeaseResult)] =
    state.get(command.resourceKey) match
      case Some(existing) if existing.isActiveAt(now) => Left(LeaseError.AlreadyHeld(existing))
      case previous =>
        val priorFencingToken = previous.map(_.fencingToken).getOrElse(0L)
        val priorVersion = previous.map(_.version).getOrElse(0L)
        val record = LeaseRecord(
          leaseId = command.leaseId,
          resourceKey = command.resourceKey,
          holderId = command.holderId,
          status = LeaseStatus.Active,
          acquiredAt = now,
          expiresAt = now.plusSeconds(command.ttlSeconds),
          releasedAt = None,
          lastRenewedAt = None,
          fencingToken = priorFencingToken + 1L,
          version = priorVersion + 1L
        )
        val nextState = LeaseState(state.leases.updated(command.resourceKey, record))
        Right(nextState -> LeaseResult.Acquired(record))

  private def handleRenew(state: LeaseState, command: Renew, now: Instant): Either[LeaseError, (LeaseState, LeaseResult)] =
    state.get(command.resourceKey) match
      case None => Left(LeaseError.NotFound(command.resourceKey))
      case Some(existing) if !existing.isActiveAt(now) => Left(LeaseError.LeaseExpired(command.resourceKey))
      case Some(existing) if existing.leaseId != command.leaseId || existing.holderId != command.holderId =>
        Left(LeaseError.LeaseMismatch(command.resourceKey))
      case Some(existing) =>
        val renewed = existing.copy(
          expiresAt = now.plusSeconds(command.ttlSeconds),
          lastRenewedAt = Some(now),
          version = existing.version + 1L
        )
        val nextState = LeaseState(state.leases.updated(command.resourceKey, renewed))
        Right(nextState -> LeaseResult.Renewed(renewed))

  private def handleRelease(state: LeaseState, command: Release, now: Instant): Either[LeaseError, (LeaseState, LeaseResult)] =
    state.get(command.resourceKey) match
      case None => Left(LeaseError.NotFound(command.resourceKey))
      case Some(existing) if !existing.isActiveAt(now) => Left(LeaseError.LeaseExpired(command.resourceKey))
      case Some(existing) if existing.leaseId != command.leaseId || existing.holderId != command.holderId =>
        Left(LeaseError.LeaseMismatch(command.resourceKey))
      case Some(existing) =>
        val released = existing.copy(status = LeaseStatus.Released, releasedAt = Some(now), version = existing.version + 1L)
        val nextState = LeaseState(state.leases.updated(command.resourceKey, released))
        Right(nextState -> LeaseResult.Released(released))
