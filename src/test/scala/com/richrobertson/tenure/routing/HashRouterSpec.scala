package com.richrobertson.tenure.routing

import com.richrobertson.tenure.group.GroupId
import com.richrobertson.tenure.model.{ResourceId, TenantId}
import munit.FunSuite

class HashRouterSpec extends FunSuite:
  private val tenantId = TenantId("tenant-a")
  private val groupIds = Vector(GroupId("group-1"), GroupId("group-2"), GroupId("group-3"))

  test("same resource key always maps to the same group") {
    val router = HashRouter(groupIds)
    val first = router.route(tenantId, ResourceId("resource-42"))
    val second = router.route(tenantId, ResourceId("resource-42"))
    val third = router.route(tenantId, ResourceId("resource-42"))

    assertEquals(second.groupId, first.groupId)
    assertEquals(third.groupId, first.groupId)
  }

  test("different keys distribute across configured groups deterministically") {
    val router = HashRouter(groupIds)
    val mappedGroups = (1 to 256).toVector
      .map(idx => router.route(tenantId, ResourceId(s"resource-$idx")).groupId)
      .distinct

    assertEquals(mappedGroups.sortBy(_.value), groupIds.sortBy(_.value))
  }
