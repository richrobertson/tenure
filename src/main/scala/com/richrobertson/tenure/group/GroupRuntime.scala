package com.richrobertson.tenure.group

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.richrobertson.tenure.auth.Authorization
import com.richrobertson.tenure.observability.Observability
import com.richrobertson.tenure.quota.TenantQuotaRegistry
import com.richrobertson.tenure.raft.RaftNode
import com.richrobertson.tenure.service.{LeaseService, LeaseServiceRuntime, ServiceState}
import com.richrobertson.tenure.time.Clock

final case class GroupId(value: String) derives CanEqual
object GroupId:
  val Default: GroupId = GroupId("group-1")

trait GroupRuntime[F[_]]:
  def groupId: GroupId
  def service: LeaseService[F]
  def readState: F[ServiceState]

object GroupRuntime:
  def inMemory[F[_]: Async](groupId: GroupId, clock: Clock[F]): F[GroupRuntime[F]] =
    inMemory(groupId, clock, TenantQuotaRegistry.default, Authorization.perTenant, Observability.noop[F])

  def inMemory[F[_]: Async](groupId: GroupId, clock: Clock[F], observability: Observability[F]): F[GroupRuntime[F]] =
    inMemory(groupId, clock, TenantQuotaRegistry.default, Authorization.perTenant, observability)

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

  def replicated[F[_]: Async](groupId: GroupId, raftNode: RaftNode[F], clock: Clock[F]): GroupRuntime[F] =
    replicated(groupId, raftNode, clock, TenantQuotaRegistry.default, Authorization.perTenant, Observability.noop[F])

  def replicated[F[_]: Async](groupId: GroupId, raftNode: RaftNode[F], clock: Clock[F], observability: Observability[F]): GroupRuntime[F] =
    replicated(groupId, raftNode, clock, TenantQuotaRegistry.default, Authorization.perTenant, observability)

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

  private def scopedObservability[F[_]](groupId: GroupId, observability: Observability[F]): Observability[F] =
    Observability.scoped(observability, Map("group_id" -> groupId.value), Map("group_id" -> groupId.value))

private final case class DefaultGroupRuntime[F[_]](groupId: GroupId, runtime: LeaseServiceRuntime[F]) extends GroupRuntime[F]:
  override def service: LeaseService[F] = runtime.service
  override def readState: F[ServiceState] = runtime.readState
