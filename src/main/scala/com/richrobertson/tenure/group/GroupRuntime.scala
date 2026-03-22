/**
 * Per-group execution boundary helpers.
 *
 * A [[GroupRuntime]] bundles the lease service surface for one logical Raft group together with a read hook
 * for its current state. In v1 that is usually one real group, but the abstraction also supports local
 * multi-group simulations.
 */
package com.richrobertson.tenure.group

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.richrobertson.tenure.auth.Authorization
import com.richrobertson.tenure.observability.Observability
import com.richrobertson.tenure.quota.TenantQuotaRegistry
import com.richrobertson.tenure.raft.RaftNode
import com.richrobertson.tenure.service.{LeaseService, LeaseServiceRuntime, ServiceState}
import com.richrobertson.tenure.time.Clock

/** Logical identifier for one execution group. */
final case class GroupId(value: String) derives CanEqual

/** Common [[GroupId]] values. */
object GroupId:
  /** Default v1 single-group identifier. */
  val Default: GroupId = GroupId("group-1")

/** Runtime bundle for one logical group. */
trait GroupRuntime[F[_]]:
  /** Stable group identifier. */
  def groupId: GroupId

  /** Lease service bound to this group. */
  def service: LeaseService[F]

  /** Effect that reads the current materialized [[ServiceState]] for this group. */
  def readState: F[ServiceState]

/** Constructors for in-memory and replicated [[GroupRuntime]] values. */
object GroupRuntime:
  /** Builds an in-memory runtime with default quotas, auth, and no-op observability. */
  def inMemory[F[_]: Async](groupId: GroupId, clock: Clock[F]): F[GroupRuntime[F]] =
    inMemory(groupId, clock, TenantQuotaRegistry.default, Authorization.perTenant, Observability.noop[F])

  /** Builds an in-memory runtime with caller-supplied observability. */
  def inMemory[F[_]: Async](groupId: GroupId, clock: Clock[F], observability: Observability[F]): F[GroupRuntime[F]] =
    inMemory(groupId, clock, TenantQuotaRegistry.default, Authorization.perTenant, observability)

  /**
   * Builds an in-memory runtime.
   *
   * This is most useful for unit tests, local demos, and higher-level routing simulations where consensus
   * behavior is not the focus.
   */
  def inMemory[F[_]: Async](
      groupId: GroupId,
      clock: Clock[F],
      quotas: TenantQuotaRegistry,
      authorization: Authorization,
      observability: Observability[F]
  ): F[GroupRuntime[F]] =
    LeaseService
      .inMemoryRuntime(clock, quotas, authorization, scopedObservability(groupId, observability))
      .map(runtime => DefaultGroupRuntime(groupId, runtime))

  /** Builds a replicated runtime with default quotas, auth, and no-op observability. */
  def replicated[F[_]: Async](groupId: GroupId, raftNode: RaftNode[F], clock: Clock[F]): GroupRuntime[F] =
    replicated(groupId, raftNode, clock, TenantQuotaRegistry.default, Authorization.perTenant, Observability.noop[F])

  /** Builds a replicated runtime with caller-supplied observability. */
  def replicated[F[_]: Async](groupId: GroupId, raftNode: RaftNode[F], clock: Clock[F], observability: Observability[F]): GroupRuntime[F] =
    replicated(groupId, raftNode, clock, TenantQuotaRegistry.default, Authorization.perTenant, observability)

  /**
   * Builds a replicated runtime backed by a real [[RaftNode]].
   *
   * This is the main production-shaped entry point for group-local lease behavior.
   */
  def replicated[F[_]: Async](
      groupId: GroupId,
      raftNode: RaftNode[F],
      clock: Clock[F],
      quotas: TenantQuotaRegistry,
      authorization: Authorization,
      observability: Observability[F]
  ): GroupRuntime[F] =
    DefaultGroupRuntime(
      groupId,
      LeaseService.replicatedRuntime(raftNode, clock, quotas, authorization, scopedObservability(groupId, observability))
    )

  /** Adds group-scoped labels and fields to observability output. */
  private def scopedObservability[F[_]](groupId: GroupId, observability: Observability[F]): Observability[F] =
    Observability.scoped(observability, Map("group_id" -> groupId.value), Map("group_id" -> groupId.value))

private final case class DefaultGroupRuntime[F[_]](groupId: GroupId, runtime: LeaseServiceRuntime[F]) extends GroupRuntime[F]:
  override def service: LeaseService[F] = runtime.service
  override def readState: F[ServiceState] = runtime.readState
