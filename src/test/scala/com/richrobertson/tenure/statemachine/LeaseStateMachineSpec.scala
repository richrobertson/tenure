package com.richrobertson.tenure.statemachine

import com.richrobertson.tenure.model.*
import munit.FunSuite
import java.time.Instant
import java.util.UUID

class LeaseStateMachineSpec extends FunSuite:
  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")
  private val resource = ResourceId("resource-1")
  private val holder = ClientId("holder-1")
  private val otherHolder = ClientId("holder-2")
  private val keyA = ResourceKey(tenantA, resource)
  private val keyB = ResourceKey(tenantB, resource)
  private val start = Instant.parse("2026-03-20T12:00:00Z")

  test("acquire succeeds for an unleased resource") {
    val result = LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 15, LeaseId(UUID.randomUUID())), start)
    assert(result.isRight)
  }

  test("acquire rejects non-positive ttl in the pure core") {
    val result = LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 0, LeaseId(UUID.randomUUID())), start)
    assertEquals(result.left.toOption, Some(LeaseError.Validation("ttl_seconds must be positive")))
  }

  test("second acquire fails while active") {
    val leaseId = LeaseId(UUID.randomUUID())
    val state = successfulState(LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 15, leaseId), start))
    val second = LeaseStateMachine.transition(state, Acquire(keyA, otherHolder, 15, LeaseId(UUID.randomUUID())), start.plusSeconds(5))
    assert(second.left.exists(_.isInstanceOf[LeaseError.AlreadyHeld]))
  }

  test("acquire succeeds after expiry") {
    val leaseId = LeaseId(UUID.randomUUID())
    val state = successfulState(LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 5, leaseId), start))
    val later = LeaseStateMachine.transition(state, Acquire(keyA, otherHolder, 10, LeaseId(UUID.randomUUID())), start.plusSeconds(5))
    assert(later.isRight)
  }

  test("renew succeeds for current holder before expiry") {
    val leaseId = LeaseId(UUID.randomUUID())
    val state = successfulState(LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 15, leaseId), start))
    val renewed = LeaseStateMachine.transition(state, Renew(keyA, leaseId, holder, 30), start.plusSeconds(10))
    assert(renewed.isRight)
  }

  test("renew fails for wrong holder") {
    val leaseId = LeaseId(UUID.randomUUID())
    val state = successfulState(LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 15, leaseId), start))
    val renewed = LeaseStateMachine.transition(state, Renew(keyA, leaseId, otherHolder, 30), start.plusSeconds(10))
    assert(renewed.left.exists(_.isInstanceOf[LeaseError.LeaseMismatch]))
  }

  test("release succeeds for current holder") {
    val leaseId = LeaseId(UUID.randomUUID())
    val state = successfulState(LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 15, leaseId), start))
    val released = LeaseStateMachine.transition(state, Release(keyA, leaseId, holder), start.plusSeconds(2))
    assert(released.isRight)
  }

  test("release fails for wrong holder") {
    val leaseId = LeaseId(UUID.randomUUID())
    val state = successfulState(LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 15, leaseId), start))
    val released = LeaseStateMachine.transition(state, Release(keyA, leaseId, otherHolder), start.plusSeconds(2))
    assert(released.left.exists(_.isInstanceOf[LeaseError.LeaseMismatch]))
  }

  test("tenant scoping keeps same resource id independent") {
    val state = successfulState(LeaseStateMachine.transition(LeaseState.empty, Acquire(keyA, holder, 15, LeaseId(UUID.randomUUID())), start))
    val second = LeaseStateMachine.transition(state, Acquire(keyB, otherHolder, 15, LeaseId(UUID.randomUUID())), start.plusSeconds(1))
    assert(second.isRight)
  }

  private def successfulState(result: Either[LeaseError, (LeaseState, LeaseResult)]): LeaseState =
    result match
      case Right((state, _)) => state
      case Left(error)       => fail(s"expected successful state transition, got $error")
