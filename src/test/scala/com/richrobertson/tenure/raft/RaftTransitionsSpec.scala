package com.richrobertson.tenure.raft

import com.richrobertson.tenure.model.*
import com.richrobertson.tenure.service.*
import munit.FunSuite
import java.time.Instant
import java.util.UUID

class RaftTransitionsSpec extends FunSuite:
  private val appliedAt = Instant.parse("2026-03-21T12:00:00Z")
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

  test("old-term majority match does not advance commit index by itself") {
    val priorTermEntry = logEntry(index = 1L, term = 1L, requestId = "req-1")
    val leaderState = emptyState.copy(
      currentTerm = 2L,
      role = NodeRole.Leader,
      log = Vector(priorTermEntry),
      peerProgress = Map("node-b" -> PeerProgress(nextIndex = 2L, matchIndex = 1L), "node-c" -> PeerProgress(nextIndex = 1L, matchIndex = 0L))
    )

    assertEquals(RaftTransitions.majorityMatchedIndex(leaderState, majority = 2), 1L)
    assertEquals(RaftTransitions.commitIndexToAdvance(leaderState, majority = 2), None)
  }

  test("current-term majority match allows commit advancement and implicitly covers older prefix") {
    val priorTermEntry = logEntry(index = 1L, term = 1L, requestId = "req-1")
    val currentTermEntry = logEntry(index = 2L, term = 2L, requestId = "req-2")
    val leaderState = emptyState.copy(
      currentTerm = 2L,
      role = NodeRole.Leader,
      log = Vector(priorTermEntry, currentTermEntry),
      peerProgress = Map("node-b" -> PeerProgress(nextIndex = 3L, matchIndex = 2L), "node-c" -> PeerProgress(nextIndex = 1L, matchIndex = 0L))
    )

    assertEquals(RaftTransitions.majorityMatchedIndex(leaderState, majority = 2), 2L)
    assertEquals(RaftTransitions.commitIndexToAdvance(leaderState, majority = 2), Some(2L))
  }

  test("steady-state current-term majority still commits the normal happy path") {
    val currentTermEntry = logEntry(index = 1L, term = 2L, requestId = "req-1")
    val leaderState = emptyState.copy(
      currentTerm = 2L,
      role = NodeRole.Leader,
      log = Vector(currentTermEntry),
      peerProgress = Map("node-b" -> PeerProgress(nextIndex = 2L, matchIndex = 1L), "node-c" -> PeerProgress(nextIndex = 1L, matchIndex = 0L))
    )

    assertEquals(RaftTransitions.majorityMatchedIndex(leaderState, majority = 2), 1L)
    assertEquals(RaftTransitions.commitIndexToAdvance(leaderState, majority = 2), Some(1L))
  }

  private def logEntry(index: Long, term: Long, requestId: String): RaftLogEntry =
    val uuid = UUID.fromString("00000000-0000-0000-0000-" + f"$index%012d")
    RaftLogEntry(index, term, AcquireCommand(requestContext(requestId), ClientId("holder-1"), 30L, LeaseId(uuid), appliedAt))

  private def requestContext(requestId: String): RequestContext =
    val tenantId = TenantId("tenant-a")
    val resourceId = ResourceId(s"resource-$requestId")
    RequestContext(tenantId, RequestId(requestId), ResourceKey(tenantId, resourceId))
