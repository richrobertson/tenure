package com.richrobertson.tenure.routing

import com.richrobertson.tenure.group.GroupId
import com.richrobertson.tenure.model.{ResourceKey, ResourceId, TenantId}

import java.nio.charset.StandardCharsets

final case class RoutingDecision(resourceKey: ResourceKey, groupId: GroupId) derives CanEqual

trait Router:
  def groups: Vector[GroupId]
  def route(resourceKey: ResourceKey): RoutingDecision

  final def route(tenantId: TenantId, resourceId: ResourceId): RoutingDecision =
    route(ResourceKey(tenantId, resourceId))

object Router:
  def single(groupId: GroupId = GroupId.Default): Router = HashRouter(Vector(groupId))

final case class HashRouter(groups: Vector[GroupId]) extends Router:
  require(groups.nonEmpty, "router must contain at least one group")

  private val orderedGroups = groups.distinct

  override def route(resourceKey: ResourceKey): RoutingDecision =
    val hash = stableHash64(resourceKey)
    val index = Math.floorMod(hash, orderedGroups.size.toLong).toInt
    RoutingDecision(resourceKey, orderedGroups(index))

  private def stableHash64(resourceKey: ResourceKey): Long =
    val bytes = s"${resourceKey.tenantId.value}\u0000${resourceKey.resourceId.value}".getBytes(StandardCharsets.UTF_8)
    bytes.foldLeft(0xcbf29ce484222325L) { (hash, byte) =>
      (hash ^ (byte & 0xff).toLong) * 0x100000001b3L
    }
