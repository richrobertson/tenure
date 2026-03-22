package com.richrobertson.tenure.raft

import munit.FunSuite

class RaftConfigSpec extends FunSuite:
  test("peer endpoints bracket IPv6 literals") {
    val peer = PeerNode("node-1", "::1", 9001, "2001:db8::10", 9101)

    assertEquals(peer.endpoint, "[::1]:9001")
    assertEquals(peer.apiEndpoint, "[2001:db8::10]:9101")
  }

  test("leader hint endpoint uses bracketed IPv6 API endpoints") {
    val peer = PeerNode("node-1", "127.0.0.1", 9001, "::1", 9101)
    val config = ClusterConfig("node-1", "::1", 9101, List(peer), "/tmp/tenure-raft-config-spec")

    assertEquals(config.leaderHintEndpoint("node-1"), Some("[::1]:9101"))
  }
