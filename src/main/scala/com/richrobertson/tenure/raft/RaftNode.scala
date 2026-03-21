package com.richrobertson.tenure.raft

import cats.effect.kernel.{Async, Resource, Temporal}
import cats.effect.std.Mutex
import cats.effect.{Fiber, Ref}
import cats.syntax.all.*
import com.richrobertson.tenure.persistence.{RaftPersistence, RecoveryBootstrap}
import com.richrobertson.tenure.quota.TenantQuotaRegistry
import com.richrobertson.tenure.service.*
import com.richrobertson.tenure.service.ServiceCodecs.given
import io.circe.Codec
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

enum NodeRole derives CanEqual:
  case Leader, Follower, Candidate

final case class NotLeader(leaderHint: Option[String]) derives CanEqual
final case class PersistedMetadata(currentTerm: Long, votedFor: Option[String], commitIndex: Long, lastApplied: Long) derives CanEqual
object PersistedMetadata:
  val initial: PersistedMetadata = PersistedMetadata(0L, None, 0L, 0L)
  given Codec[PersistedMetadata] = deriveCodec

final case class RaftLogEntry(index: Long, term: Long, command: ReplicatedCommand) derives CanEqual
object RaftLogEntry:
  given Codec[RaftLogEntry] = deriveCodec

final case class PersistedSnapshot(formatVersion: Int, lastIncludedIndex: Long, lastIncludedTerm: Long, serviceState: ServiceState) derives CanEqual
object PersistedSnapshot:
  val formatVersionV1 = 1
  given Codec[PersistedSnapshot] = deriveCodec

final case class PersistedNodeState(metadata: PersistedMetadata, snapshot: Option[PersistedSnapshot], entries: Vector[RaftLogEntry]) derives CanEqual
object PersistedNodeState:
  given Codec[PersistedNodeState] = deriveCodec

final case class AppendEntriesRequest(term: Long, leaderId: String, prevLogIndex: Long, prevLogTerm: Long, entries: Vector[RaftLogEntry], leaderCommit: Long) derives CanEqual
final case class AppendEntriesResponse(term: Long, success: Boolean, matchIndex: Long) derives CanEqual
final case class VoteRequest(term: Long, candidateId: String, lastLogIndex: Long, lastLogTerm: Long) derives CanEqual
final case class VoteResponse(term: Long, voteGranted: Boolean) derives CanEqual
final case class PeerProgress(nextIndex: Long, matchIndex: Long) derives CanEqual

enum PeerMessage derives CanEqual:
  case AppendEntries(request: AppendEntriesRequest)
  case AppendEntriesAck(response: AppendEntriesResponse)
  case RequestVote(request: VoteRequest)
  case RequestVoteAck(response: VoteResponse)

object PeerMessage:
  given Codec[AppendEntriesRequest] = deriveCodec
  given Codec[AppendEntriesResponse] = deriveCodec
  given Codec[VoteRequest] = deriveCodec
  given Codec[VoteResponse] = deriveCodec
  given Codec[PeerMessage] = Codec.from(peerMessageDecoder, peerMessageEncoder)

  private val peerMessageDecoder: Decoder[PeerMessage] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "append_entries"     => cursor.get[AppendEntriesRequest]("payload").map(PeerMessage.AppendEntries.apply)
      case "append_entries_ack" => cursor.get[AppendEntriesResponse]("payload").map(PeerMessage.AppendEntriesAck.apply)
      case "request_vote"       => cursor.get[VoteRequest]("payload").map(PeerMessage.RequestVote.apply)
      case "request_vote_ack"   => cursor.get[VoteResponse]("payload").map(PeerMessage.RequestVoteAck.apply)
      case other                 => Left(io.circe.DecodingFailure(s"unknown type $other", cursor.history))
    }
  }

  private val peerMessageEncoder: Encoder[PeerMessage] = Encoder.instance {
    case PeerMessage.AppendEntries(payload)    => Json.obj("type" -> Json.fromString("append_entries"), "payload" -> payload.asJson)
    case PeerMessage.AppendEntriesAck(payload) => Json.obj("type" -> Json.fromString("append_entries_ack"), "payload" -> payload.asJson)
    case PeerMessage.RequestVote(payload)      => Json.obj("type" -> Json.fromString("request_vote"), "payload" -> payload.asJson)
    case PeerMessage.RequestVoteAck(payload)   => Json.obj("type" -> Json.fromString("request_vote_ack"), "payload" -> payload.asJson)
  }

final case class RaftRuntimeState(
    currentTerm: Long,
    votedFor: Option[String],
    role: NodeRole,
    leaderId: Option[String],
    log: Vector[RaftLogEntry],
    snapshot: Option[PersistedSnapshot],
    commitIndex: Long,
    lastApplied: Long,
    materialized: ServiceState,
    lastHeartbeatMillis: Long,
    lastQuorumAckMillis: Long,
    peerProgress: Map[String, PeerProgress]
) derives CanEqual

object RaftTransitions:
  private def lastIndex(state: RaftRuntimeState): Long =
    state.log.lastOption.map(_.index).orElse(state.snapshot.map(_.lastIncludedIndex)).getOrElse(0L)

  private def termAt(state: RaftRuntimeState, index: Long): Option[Long] =
    if index == 0 then Some(0L)
    else
      state.snapshot.filter(_.lastIncludedIndex == index).map(_.lastIncludedTerm)
        .orElse(state.log.find(_.index == index).map(_.term))

  def handleVoteRequest(state: RaftRuntimeState, request: VoteRequest, now: Long): (RaftRuntimeState, VoteResponse) =
    if request.term < state.currentTerm then state -> VoteResponse(state.currentTerm, voteGranted = false)
    else
      val termAdjusted =
        if request.term > state.currentTerm then state.copy(currentTerm = request.term, votedFor = None, role = NodeRole.Follower, leaderId = None)
        else state
      val localLastIndex = lastIndex(termAdjusted)
      val localLastTerm = termAt(termAdjusted, localLastIndex).getOrElse(0L)
      val upToDate = request.lastLogTerm > localLastTerm ||
        (request.lastLogTerm == localLastTerm && request.lastLogIndex >= localLastIndex)
      val canVote = termAdjusted.votedFor.forall(_ == request.candidateId)
      val granted = canVote && upToDate
      val next = if granted then termAdjusted.copy(votedFor = Some(request.candidateId), lastHeartbeatMillis = now) else termAdjusted.copy(lastHeartbeatMillis = now)
      next -> VoteResponse(next.currentTerm, granted)

  def handleAppendEntries(state: RaftRuntimeState, request: AppendEntriesRequest, now: Long, quotas: TenantQuotaRegistry = TenantQuotaRegistry.default): (RaftRuntimeState, AppendEntriesResponse) =
    if request.term < state.currentTerm then state -> AppendEntriesResponse(state.currentTerm, success = false, lastIndex(state))
    else
      val base =
        if request.term > state.currentTerm then
          state.copy(currentTerm = request.term, role = NodeRole.Follower, leaderId = Some(request.leaderId), votedFor = None, lastHeartbeatMillis = now)
        else
          state.copy(role = NodeRole.Follower, leaderId = Some(request.leaderId), lastHeartbeatMillis = now)
      val prevMatches =
        if request.prevLogIndex == 0 then true
        else termAt(base, request.prevLogIndex).contains(request.prevLogTerm)
      if !prevMatches then base -> AppendEntriesResponse(base.currentTerm, success = false, lastIndex(base))
      else
        val prefix = base.log.takeWhile(_.index <= request.prevLogIndex)
        val nextLog = if request.entries.isEmpty then base.log else prefix ++ request.entries
        val cappedCommit = math.min(request.leaderCommit, nextLog.lastOption.map(_.index).orElse(base.snapshot.map(_.lastIncludedIndex)).getOrElse(base.commitIndex))
        val entriesToApply = nextLog.filter(entry => entry.index > base.lastApplied && entry.index <= cappedCommit).sortBy(_.index)
        val materialized = entriesToApply.foldLeft(base.materialized) { case (acc, entry) => LeaseMaterializer.applyCommand(acc, entry.command) }
        val next = base.copy(log = nextLog, commitIndex = cappedCommit, lastApplied = math.max(base.lastApplied, cappedCommit), materialized = materialized)
        next -> AppendEntriesResponse(next.currentTerm, success = true, lastIndex(next))

  def majorityMatchedIndex(state: RaftRuntimeState, majority: Int): Long =
    val matchIndexes = state.peerProgress.values.map(_.matchIndex).toList :+ lastIndex(state)
    val candidates = matchIndexes.sorted
    candidates.drop(candidates.size - majority).headOption.getOrElse(0L)

  // Raft leaders may advance commitIndex by replica counting only for entries from their current term.
  def eligibleCommitIndexForCurrentTerm(state: RaftRuntimeState, majority: Int): Option[Long] =
    val candidate = majorityMatchedIndex(state, majority)
    if candidate <= state.commitIndex then None
    else
      termAt(state, candidate).filter(_ == state.currentTerm).map(_ => candidate)

trait RaftNode[F[_]]:
  def nodeId: String
  def submit(command: ReplicatedCommand): F[Either[NotLeader, StoredResult]]
  def role: F[NodeRole]
  def leaderHint: F[Option[String]]
  def readState: F[ServiceState]
  def canServeLeaderReads: F[Boolean]
  def shutdown: F[Unit]

object RaftNode:
  def resource[F[_]: Async](config: ClusterConfig, persistence: RaftPersistence[F], quotas: TenantQuotaRegistry = TenantQuotaRegistry.default): Resource[F, RaftNode[F]] =
    Resource.make(create(config, persistence, quotas))(_.shutdown).widen

  private def create[F[_]: Async](config: ClusterConfig, persistence: RaftPersistence[F], quotas: TenantQuotaRegistry): F[LiveRaftNode[F]] =
    for
      recovered <- RecoveryBootstrap.recover(persistence)
      nowMillis <- Temporal[F].realTime.map(_.toMillis)
      stateRef <- Ref.of[F, RaftRuntimeState](
        RaftRuntimeState(
          currentTerm = recovered.persisted.metadata.currentTerm,
          votedFor = recovered.persisted.metadata.votedFor,
          role = NodeRole.Follower,
          leaderId = None,
          log = recovered.persisted.entries,
          snapshot = recovered.persisted.snapshot,
          commitIndex = recovered.commitIndex,
          lastApplied = recovered.lastApplied,
          materialized = recovered.materialized,
          lastHeartbeatMillis = nowMillis,
          lastQuorumAckMillis = 0L,
          peerProgress = Map.empty
        )
      )
      mutex <- Mutex[F]
      serverRef <- Ref.of[F, Option[ServerSocket]](None)
      fibersRef <- Ref.of[F, List[Fiber[F, Throwable, Unit]]](Nil)
      node = new LiveRaftNode[F](config, persistence, stateRef, mutex, serverRef, fibersRef, quotas)
      _ <- node.start
    yield node

private final class LiveRaftNode[F[_]: Async](
    config: ClusterConfig,
    persistence: RaftPersistence[F],
    stateRef: Ref[F, RaftRuntimeState],
    mutex: Mutex[F],
    serverRef: Ref[F, Option[ServerSocket]],
    fibersRef: Ref[F, List[Fiber[F, Throwable, Unit]]],
    quotas: TenantQuotaRegistry
) extends RaftNode[F]:
  override val nodeId: String = config.nodeId

  private val electionMin = 400.millis
  private val electionMax = 650.millis
  private val heartbeatInterval = 150.millis
  private val quorumLeaseTimeout = 900.millis
  private val peerIoTimeoutMillis = 1000
  private val snapshotThreshold = 5L

  def start: F[Unit] =
    for
      serverFiber <- Async[F].start(serverLoop)
      electionFiber <- Async[F].start(electionLoop)
      heartbeatFiber <- Async[F].start(heartbeatLoop)
      _ <- fibersRef.set(List(serverFiber, electionFiber, heartbeatFiber))
    yield ()

  override def shutdown: F[Unit] =
    for
      server <- serverRef.get
      _ <- server.traverse_(socket => Async[F].blocking(socket.close()).handleError(_ => ()))
      fibers <- fibersRef.get
      _ <- fibers.traverse_(_.cancel)
    yield ()

  override def submit(command: ReplicatedCommand): F[Either[NotLeader, StoredResult]] =
    mutex.lock.surround {
      assertLeaderAuthority.flatMap {
        case Left(notLeader) => notLeader.asLeft[StoredResult].pure[F]
        case Right(leaderState) =>
          val entry = RaftLogEntry(lastLogIndex(leaderState) + 1L, leaderState.currentTerm, command)
          for
            _ <- persistence.appendEntry(entry)
            _ <- stateRef.update(current => current.copy(log = current.log :+ entry))
            updated <- stateRef.get
            replication <- replicateToQuorum(updated)
            result <-
              if replication.authoritative then readCommittedResult(command.requestContext)
              else stepDown(updated.currentTerm, None) *> NotLeader(updated.leaderId.flatMap(config.leaderHintEndpoint)).asLeft[StoredResult].pure[F]
          yield result
      }
    }

  override def role: F[NodeRole] = stateRef.get.map(_.role)
  override def leaderHint: F[Option[String]] = stateRef.get.map(_.leaderId.flatMap(config.leaderHintEndpoint))
  override def readState: F[ServiceState] = stateRef.get.map(_.materialized)

  override def canServeLeaderReads: F[Boolean] =
    stateRef.get.flatMap(canServeLeaderReadsFromState)

  private def canServeLeaderReadsFromState(state: RaftRuntimeState): F[Boolean] =
    Temporal[F].realTime.map(_.toMillis).map { now =>
      state.role == NodeRole.Leader && (config.raftPeers.size == 1 || now - state.lastQuorumAckMillis <= quorumLeaseTimeout.toMillis)
    }

  private def assertLeaderAuthority: F[Either[NotLeader, RaftRuntimeState]] =
    stateRef.get.flatMap { state =>
      canServeLeaderReadsFromState(state).map { ready =>
        if state.role == NodeRole.Leader && ready then Right(state)
        else Left(NotLeader(state.leaderId.flatMap(config.leaderHintEndpoint)))
      }
    }

  private def serverLoop: F[Unit] =
    Async[F].bracket(
      Async[F].blocking {
        val server = new ServerSocket()
        server.setReuseAddress(true)
        server.bind(new InetSocketAddress(config.localPeer.host, config.localPeer.port))
        server
      }
    )(server => serverRef.set(Some(server)) *> acceptLoop(server))(server =>
      serverRef.set(None) *> Async[F].blocking(server.close()).handleError(_ => ())
    )

  private def acceptLoop(server: ServerSocket): F[Unit] =
    Async[F].blocking(server.accept()).attempt.flatMap {
      case Right(socket) => Async[F].start(handleSocket(socket)).void *> acceptLoop(server)
      case Left(_)       => Async[F].unit
    }

  private def electionLoop: F[Unit] =
    randomElectionTimeout.flatMap(Temporal[F].sleep) *> stateRef.get.flatMap { state =>
      Temporal[F].realTime.map(_.toMillis).flatMap { now =>
        val recentlyHeardLeader = now - state.lastHeartbeatMillis < electionMin.toMillis
        if state.role == NodeRole.Leader || recentlyHeardLeader then electionLoop
        else startElection *> electionLoop
      }
    }

  private def heartbeatLoop: F[Unit] =
    Temporal[F].sleep(heartbeatInterval) *> stateRef.get.flatMap { state =>
      val tick =
        if state.role == NodeRole.Leader then
          replicateToQuorum(state).flatMap { result =>
            if result.quorumAcknowledged || result.authoritative then Async[F].unit
            else stepDown(state.currentTerm, None)
          }
        else Async[F].unit
      tick *> heartbeatLoop
    }

  private def startElection: F[Unit] =
    mutex.lock.surround {
      for
        now <- Temporal[F].realTime.map(_.toMillis)
        candidate <- stateRef.modify { state =>
          val next = state.copy(
            currentTerm = state.currentTerm + 1L,
            votedFor = Some(config.nodeId),
            role = NodeRole.Candidate,
            leaderId = None,
            lastHeartbeatMillis = now
          )
          next -> next
        }
        _ <- persistMetadata(candidate)
        lastIndex = lastLogIndex(candidate)
        lastTerm = termAt(candidate, lastIndex).getOrElse(0L)
        votes <- config.raftPeers.filterNot(_.nodeId == config.nodeId).traverse { peer =>
          send(peer, PeerMessage.RequestVote(VoteRequest(candidate.currentTerm, config.nodeId, lastIndex, lastTerm))).attempt.flatMap {
            case Right(PeerMessage.RequestVoteAck(response)) =>
              if response.term > candidate.currentTerm then stepDown(response.term, None).as(false)
              else response.voteGranted.pure[F]
            case _ => false.pure[F]
          }
        }
        _ <-
          if 1 + votes.count(identity) >= config.majority then becomeLeader(now)
          else Async[F].unit
      yield ()
    }

  private def becomeLeader(nowMillis: Long): F[Unit] =
    stateRef.update { state =>
      val nextLogIndex = lastLogIndex(state)
      val progress = config.raftPeers.filterNot(_.nodeId == config.nodeId).map { peer =>
        peer.nodeId -> PeerProgress(nextIndex = nextLogIndex + 1L, matchIndex = state.snapshot.map(_.lastIncludedIndex).getOrElse(0L))
      }.toMap
      state.copy(
        role = NodeRole.Leader,
        leaderId = Some(config.nodeId),
        lastQuorumAckMillis = nowMillis,
        peerProgress = progress
      )
    }

  private final case class ReplicationOutcome(authoritative: Boolean, quorumAcknowledged: Boolean)

  private def replicateToQuorum(initialState: RaftRuntimeState): F[ReplicationOutcome] =
    for
      outcomes <- config.raftPeers.filterNot(_.nodeId == config.nodeId).traverse(peer => replicateToPeer(initialState.currentTerm, peer))
      current <- stateRef.get
      nextCommitIndex = RaftTransitions.eligibleCommitIndexForCurrentTerm(current, config.majority)
      _ <- nextCommitIndex.traverse_(commitThrough)
      _ <- nextCommitIndex.traverse_(_ => config.raftPeers.filterNot(_.nodeId == config.nodeId).traverse_(peer => replicateToPeer(initialState.currentTerm, peer).void))
      now <- Temporal[F].realTime.map(_.toMillis)
      successes = outcomes.count(identity) + 1
      _ <-
        if successes >= config.majority then stateRef.update(_.copy(lastQuorumAckMillis = now))
        else Async[F].unit
      refreshed <- stateRef.get
      authoritative <- canServeLeaderReadsFromState(refreshed)
    yield ReplicationOutcome(authoritative, successes >= config.majority)

  private def replicateToPeer(term: Long, peer: PeerNode): F[Boolean] =
    stateRef.get.flatMap { state =>
      state.peerProgress.get(peer.nodeId) match
        case None => false.pure[F]
        case Some(progress) => syncPeer(term, peer, progress)
    }

  private def syncPeer(term: Long, peer: PeerNode, progress: PeerProgress): F[Boolean] =
    stateRef.get.flatMap { state =>
      if state.role != NodeRole.Leader || term != state.currentTerm then false.pure[F]
      else
        val snapshotFloor = state.snapshot.map(_.lastIncludedIndex + 1L).getOrElse(1L)
        val nextIndex = math.max(snapshotFloor, progress.nextIndex)
        val prevLogIndex = nextIndex - 1L
        val prevLogTerm = termAt(state, prevLogIndex).getOrElse(0L)
        val suffix = state.log.filter(_.index >= nextIndex)
        val request = AppendEntriesRequest(state.currentTerm, config.nodeId, prevLogIndex, prevLogTerm, suffix, state.commitIndex)
        send(peer, PeerMessage.AppendEntries(request)).attempt.flatMap {
          case Right(PeerMessage.AppendEntriesAck(response)) if response.term > state.currentTerm =>
            stepDown(response.term, Some(peer.nodeId)).as(false)
          case Right(PeerMessage.AppendEntriesAck(response)) if response.success =>
            val matched = response.matchIndex
            stateRef.update { current =>
              val updated = PeerProgress(nextIndex = matched + 1L, matchIndex = matched)
              current.copy(peerProgress = current.peerProgress.updated(peer.nodeId, updated))
            }.as(true)
          case Right(PeerMessage.AppendEntriesAck(_)) if nextIndex > snapshotFloor =>
            stateRef.update(current => current.copy(peerProgress = current.peerProgress.updated(peer.nodeId, progress.copy(nextIndex = nextIndex - 1L)))).flatMap(_ =>
              syncPeer(term, peer, progress.copy(nextIndex = nextIndex - 1L))
            )
          case _ => false.pure[F]
        }
    }


  private def commitThrough(index: Long): F[Unit] =
    for
      updated <- stateRef.modify { state =>
        val entriesToApply = state.log.filter(entry => entry.index > state.lastApplied && entry.index <= index).sortBy(_.index)
        val materialized = entriesToApply.foldLeft(state.materialized) { case (acc, entry) => LeaseMaterializer.applyCommand(acc, entry.command) }
        val next = state.copy(commitIndex = index, lastApplied = index, materialized = materialized)
        next -> next
      }
      _ <- persistMetadata(updated)
      _ <- maybeSnapshotAndCompact(updated)
    yield ()

  private def readCommittedResult(requestContext: RequestContext): F[Either[NotLeader, StoredResult]] =
    stateRef.get.map { state =>
      state.materialized.responses.get((requestContext.tenantId, requestContext.requestId)).map(_.result).toRight(NotLeader(state.leaderId.flatMap(config.leaderHintEndpoint)))
    }

  private def handleSocket(socket: Socket): F[Unit] =
    Async[F].bracket(
      Async[F].blocking {
        socket.setSoTimeout(peerIoTimeoutMillis)
        val reader = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8))
        val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream, StandardCharsets.UTF_8))
        (reader, writer)
      }
    ) { case (reader, writer) =>
      Async[F].blocking(reader.readLine()).flatMap(line => Async[F].fromEither(decode[PeerMessage](line))).flatMap(handlePeerMessage).flatMap { response =>
        Async[F].blocking {
          writer.write(response.asJson.noSpaces)
          writer.write("\n")
          writer.flush()
        }
      }
    }(_ => Async[F].blocking(socket.close()).handleError(_ => ()))

  private def handlePeerMessage(message: PeerMessage): F[PeerMessage] =
    message match
      case PeerMessage.RequestVote(request)   => handleVoteRequest(request).map(PeerMessage.RequestVoteAck.apply)
      case PeerMessage.AppendEntries(request) => handleAppendEntries(request).map(PeerMessage.AppendEntriesAck.apply)
      case other                              => Async[F].raiseError(new IllegalStateException(s"unexpected inbound message $other"))

  private def handleVoteRequest(request: VoteRequest): F[VoteResponse] =
    mutex.lock.surround {
      for
        now <- Temporal[F].realTime.map(_.toMillis)
        response <- stateRef.modify(state => RaftTransitions.handleVoteRequest(state, request, now))
        current <- stateRef.get
        _ <- persistMetadata(current)
      yield response
    }

  private def handleAppendEntries(request: AppendEntriesRequest): F[AppendEntriesResponse] =
    mutex.lock.surround {
      for
        now <- Temporal[F].realTime.map(_.toMillis)
        response <- stateRef.modify(state => RaftTransitions.handleAppendEntries(state, request, now, quotas))
        current <- stateRef.get
        _ <- persistence.overwriteEntries(current.log)
        _ <- persistMetadata(current)
        _ <- maybeSnapshotAndCompact(current)
      yield response
    }

  private def stepDown(term: Long, leaderId: Option[String]): F[Unit] =
    for
      now <- Temporal[F].realTime.map(_.toMillis)
      _ <- stateRef.update { state =>
        state.copy(
          currentTerm = term,
          votedFor = Option.when(term == state.currentTerm)(state.votedFor).flatten,
          role = NodeRole.Follower,
          leaderId = leaderId,
          lastHeartbeatMillis = now,
          lastQuorumAckMillis = 0L,
          peerProgress = Map.empty
        )
      }
      current <- stateRef.get
      _ <- persistMetadata(current)
    yield ()

  private def send(peer: PeerNode, message: PeerMessage): F[PeerMessage] =
    Async[F].bracket(Async[F].blocking(new Socket()))(socket =>
      Async[F].blocking {
        socket.connect(new InetSocketAddress(peer.host, peer.port), peerIoTimeoutMillis)
        socket.setSoTimeout(peerIoTimeoutMillis)
      }.flatMap { _ =>
        Async[F].blocking {
          val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream, StandardCharsets.UTF_8))
          writer.write(message.asJson.noSpaces)
          writer.write("\n")
          writer.flush()
          val reader = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8))
          reader.readLine()
        }.flatMap(line => Async[F].fromEither(decode[PeerMessage](line)))
      }
    )(socket => Async[F].blocking(socket.close()).handleError(_ => ()))

  private def randomElectionTimeout: F[FiniteDuration] =
    Temporal[F].realTime.map(_.toMillis).map { now =>
      val spread = electionMax.toMillis - electionMin.toMillis
      val nodeOffset = config.raftPeers.indexWhere(_.nodeId == config.nodeId) match
        case -1 => 0L
        case idx => math.min(spread, idx.toLong * 83L)
      (electionMin.toMillis + ((now + nodeOffset) % (spread + 1L))).millis
    }

  private def persistMetadata(state: RaftRuntimeState): F[Unit] =
    persistence.saveMetadata(PersistedMetadata(state.currentTerm, state.votedFor, state.commitIndex, state.lastApplied))

  private def maybeSnapshotAndCompact(state: RaftRuntimeState): F[Unit] =
    val lastSnapIndex = state.snapshot.map(_.lastIncludedIndex).getOrElse(0L)
    if state.commitIndex - lastSnapIndex < snapshotThreshold then Async[F].unit
    else
      val snapshotTerm = termAt(state, state.commitIndex).getOrElse(state.currentTerm)
      val snapshot = PersistedSnapshot(
        formatVersion = PersistedSnapshot.formatVersionV1,
        lastIncludedIndex = state.commitIndex,
        lastIncludedTerm = snapshotTerm,
        serviceState = state.materialized
      )
      val compactedLog = state.log.filter(_.index > snapshot.lastIncludedIndex)
      for
        _ <- persistence.saveSnapshot(snapshot)
        _ <- persistence.overwriteEntries(compactedLog)
        compacted <- stateRef.updateAndGet(_.copy(snapshot = Some(snapshot), log = compactedLog))
        _ <- persistMetadata(compacted)
      yield ()

  private def lastLogIndex(state: RaftRuntimeState): Long =
    state.log.lastOption.map(_.index).orElse(state.snapshot.map(_.lastIncludedIndex)).getOrElse(0L)

  private def termAt(state: RaftRuntimeState, index: Long): Option[Long] =
    if index == 0 then Some(0L)
    else
      state.snapshot.filter(_.lastIncludedIndex == index).map(_.lastIncludedTerm)
        .orElse(state.log.find(_.index == index).map(_.term))
