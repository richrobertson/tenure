/**
 * Placement abstractions for mapping resources to logical Raft groups.
 *
 * v1 usually routes everything to one group, but this package preserves the `placement(tenant, resource)`
 * abstraction so later sharding work can reuse the same service surface.
 */
package com.richrobertson.tenure.routing

import com.richrobertson.tenure.group.GroupId
import com.richrobertson.tenure.model.{ResourceKey, ResourceId, TenantId}

import java.nio.charset.StandardCharsets

/** Result of routing one resource to one logical group. */
final case class RoutingDecision(resourceKey: ResourceKey, groupId: GroupId) derives CanEqual

/** Maps a resource to a target [[GroupId]]. */
trait Router:
  /** Ordered set of groups this router may choose from. */
  def groups: Vector[GroupId]

  /** Routes a fully-formed [[ResourceKey]]. */
  def route(resourceKey: ResourceKey): RoutingDecision

  /** Convenience overload that builds the [[ResourceKey]] first. */
  final def route(tenantId: TenantId, resourceId: ResourceId): RoutingDecision =
    route(ResourceKey(tenantId, resourceId))

/** Common [[Router]] constructors. */
object Router:
  /** Router that always returns a single logical group. */
  def single(groupId: GroupId = GroupId.Default): Router = HashRouter(Vector(groupId))

/**
 * Stable hash-based router.
 *
 * Newcomers: in the common single-group case this still works, it just hashes into a one-element vector.
 * The same implementation becomes useful for local multi-group simulations without changing callers.
 */
final case class HashRouter(private val rawGroups: Vector[GroupId]) extends Router:
  require(rawGroups.nonEmpty, "router must contain at least one group")

  private val orderedGroups = rawGroups.distinct

  /** Distinct ordered group set exposed publicly by the router. */
  override def groups: Vector[GroupId] = orderedGroups

  /** Deterministically routes a resource key to one configured group. */
  override def route(resourceKey: ResourceKey): RoutingDecision =
    val hash = stableHash64(resourceKey)
    val index = Math.floorMod(hash, orderedGroups.size.toLong).toInt
    RoutingDecision(resourceKey, orderedGroups(index))

  /** Stable FNV-1a-style hash over tenant and resource identity. */
  private def stableHash64(resourceKey: ResourceKey): Long =
    val bytes = s"${resourceKey.tenantId.value}\u0000${resourceKey.resourceId.value}".getBytes(StandardCharsets.UTF_8)
    bytes.foldLeft(0xcbf29ce484222325L) { (hash, byte) =>
      (hash ^ (byte & 0xff).toLong) * 0x100000001b3L
    }
