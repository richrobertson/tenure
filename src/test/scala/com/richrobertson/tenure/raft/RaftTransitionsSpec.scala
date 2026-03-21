package com.richrobertson.tenure.raft

import com.richrobertson.tenure.service.ServiceState
import munit.FunSuite

class RaftTransitionsSpec extends FunSuite:
  private val emptyState = RaftRuntimeState(
    currentTerm = 2L,
    votedFor = Some("node-a"),
    role = NodeRole.Follower,
    leaderId = None,
    log = Vector.empty,
    commitIndex = 0L,
    lastApplied = 0L,
    materialized = ServiceState.empty,
    lastHeartbeatMillis = 0L,
    lastQuorumAckMillis = 0L,
    peerProgress = Map.empty
  )

  test("vote granted once in a term") {
    val (afterFirstVote, firstResponse) = RaftTransitions.handleVoteRequest(emptyState.copy(votedFor = None), VoteRequest(2L, "node-b", 0L, 0L), 10L)
    val (_, secondResponse) = RaftTransitions.handleVoteRequest(afterFirstVote, VoteRequest(2L, "node-c", 0L, 0L), 11L)

    assertEquals(firstResponse.voteGranted, true)
    assertEquals(secondResponse.voteGranted, false)
    assertEquals(afterFirstVote.votedFor, Some("node-b"))
  }

  test("same-term append entries does not clear votedFor") {
    val (nextState, response) = RaftTransitions.handleAppendEntries(
      emptyState,
      AppendEntriesRequest(term = 2L, leaderId = "node-b", prevLogIndex = 0L, prevLogTerm = 0L, entries = Vector.empty, leaderCommit = 0L),
      now = 20L
    )

    assertEquals(response.success, true)
    assertEquals(nextState.votedFor, Some("node-a"))
  }

  test("higher-term append entries clears vote by moving to the newer term") {
    val (nextState, response) = RaftTransitions.handleAppendEntries(
      emptyState,
      AppendEntriesRequest(term = 3L, leaderId = "node-b", prevLogIndex = 0L, prevLogTerm = 0L, entries = Vector.empty, leaderCommit = 0L),
      now = 20L
    )

    assertEquals(response.success, true)
    assertEquals(nextState.currentTerm, 3L)
    assertEquals(nextState.votedFor, None)
  }
