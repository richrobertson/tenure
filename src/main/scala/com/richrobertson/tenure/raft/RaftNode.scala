package com.richrobertson.tenure.raft

import cats.effect.kernel.{Async, Resource, Temporal}
import cats.effect.std.Mutex
import cats.effect.{Fiber, Ref}
import cats.syntax.all.*
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.service.*
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
final case class PersistedMetadata(currentTerm: Long, votedFor: Option[String], commitIndex: Long) derives CanEqual
object PersistedMetadata:
  val initial: PersistedMetadata = PersistedMetadata(0L, None, 0L)
  given Codec[PersistedMetadata] = deriveCodec

final case class RaftLogEntry(index: Long, term: Long, command: ReplicatedCommand) derives CanEqual
object RaftLogEntry:
  given Codec[RaftLogEntry] = deriveCodec

final case class PersistedNodeState(metadata: PersistedMetadata, entries: Vector[RaftLogEntry]) derives CanEqual
object PersistedNodeState:
  given Codec[PersistedNodeState] = deriveCodec

final case class AppendEntriesRequest(term: Long, leaderId: String, prevLogIndex: Long, prevLogTerm: Long, entries: Vector[RaftLogEntry], leaderCommit: Long) derives CanEqual
final case class AppendEntriesResponse(term: Long, success: Boolean, matchIndex: Long) derives CanEqual
final case class VoteRequest(term: Long, candidateId: String, lastLogIndex: Long, lastLogTerm: Long) derives CanEqual
final case class VoteResponse(term: Long, voteGranted: Boolean) derives CanEqual

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
  given Codec[AcquireCommand] = deriveCodec
  given Codec[RenewCommand] = deriveCodec
  given Codec[ReleaseCommand] = deriveCodec
  given Codec[RequestContext] = deriveCodec
  given Codec[LeaseView] = deriveCodec
  given Codec[AcquireResult] = deriveCodec
  given Codec[RenewResult] = deriveCodec
  given Codec[ReleaseResult] = deriveCodec

  given Codec[ServiceError] = Codec.from(serviceErrorDecoder, serviceErrorEncoder)
  given Codec[ReplicatedCommand] = Codec.from(commandDecoder, commandEncoder)
  given Codec[StoredResult] = Codec.from(storedResultDecoder, storedResultEncoder)
  given Codec[PeerMessage] = Codec.from(peerMessageDecoder, peerMessageEncoder)

  private val serviceErrorDecoder: Decoder[ServiceError] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "invalid_request" => cursor.get[String]("message").map(ServiceError.InvalidRequest.apply)
      case "already_held"    => cursor.get[String]("message").map(ServiceError.AlreadyHeld.apply)
      case "lease_expired"   => cursor.get[String]("message").map(ServiceError.LeaseExpired.apply)
      case "lease_mismatch"  => cursor.get[String]("message").map(ServiceError.LeaseMismatch.apply)
      case "not_found"       => cursor.get[String]("message").map(ServiceError.NotFound.apply)
      case "not_leader"      => (cursor.get[String]("message"), cursor.get[Option[String]]("leader_hint")).mapN(ServiceError.NotLeader.apply)
      case other              => Left(io.circe.DecodingFailure(s"unknown service error $other", cursor.history))
    }
  }

  private val serviceErrorEncoder: Encoder[ServiceError] = Encoder.instance {
    case ServiceError.InvalidRequest(message) => Json.obj("type" -> Json.fromString("invalid_request"), "message" -> Json.fromString(message))
    case ServiceError.AlreadyHeld(message)    => Json.obj("type" -> Json.fromString("already_held"), "message" -> Json.fromString(message))
    case ServiceError.LeaseExpired(message)   => Json.obj("type" -> Json.fromString("lease_expired"), "message" -> Json.fromString(message))
    case ServiceError.LeaseMismatch(message)  => Json.obj("type" -> Json.fromString("lease_mismatch"), "message" -> Json.fromString(message))
    case ServiceError.NotFound(message)       => Json.obj("type" -> Json.fromString("not_found"), "message" -> Json.fromString(message))
    case ServiceError.NotLeader(message, leaderHint) => Json.obj("type" -> Json.fromString("not_leader"), "message" -> Json.fromString(message), "leader_hint" -> leaderHint.asJson)
  }

  private val commandDecoder: Decoder[ReplicatedCommand] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "acquire" => cursor.get[AcquireCommand]("payload")
      case "renew"   => cursor.get[RenewCommand]("payload")
      case "release" => cursor.get[ReleaseCommand]("payload")
      case other      => Left(io.circe.DecodingFailure(s"unknown command $other", cursor.history))
    }
  }

  private val commandEncoder: Encoder[ReplicatedCommand] = Encoder.instance {
    case payload: AcquireCommand => Json.obj("type" -> Json.fromString("acquire"), "payload" -> payload.asJson)
    case payload: RenewCommand   => Json.obj("type" -> Json.fromString("renew"), "payload" -> payload.asJson)
    case payload: ReleaseCommand => Json.obj("type" -> Json.fromString("release"), "payload" -> payload.asJson)
  }

  private val storedResultDecoder: Decoder[StoredResult] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "acquire" => cursor.get[Either[ServiceError, AcquireResult]]("payload").map(StoredResult.Acquire.apply)
      case "renew"   => cursor.get[Either[ServiceError, RenewResult]]("payload").map(StoredResult.Renew.apply)
      case "release" => cursor.get[Either[ServiceError, ReleaseResult]]("payload").map(StoredResult.Release.apply)
      case other      => Left(io.circe.DecodingFailure(s"unknown stored result $other", cursor.history))
    }
  }

  private val storedResultEncoder: Encoder[StoredResult] = Encoder.instance {
    case payload: StoredResult.Acquire => Json.obj("type" -> Json.fromString("acquire"), "payload" -> payload.value.asJson)
    case payload: StoredResult.Renew   => Json.obj("type" -> Json.fromString("renew"), "payload" -> payload.value.asJson)
    case payload: StoredResult.Release => Json.obj("type" -> Json.fromString("release"), "payload" -> payload.value.asJson)
  }

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
    commitIndex: Long,
    lastApplied: Long,
    materialized: ServiceState,
    lastHeartbeatMillis: Long
) derives CanEqual

trait RaftNode[F[_]]:
  def nodeId: String
  def submit(command: ReplicatedCommand): F[Either[NotLeader, StoredResult]]
  def role: F[NodeRole]
  def leaderHint: F[Option[String]]
  def readState: F[ServiceState]
  def shutdown: F[Unit]

object RaftNode:
  def resource[F[_]: Async](config: ClusterConfig, persistence: RaftPersistence[F]): Resource[F, RaftNode[F]] =
    Resource.make(create(config, persistence))(_.shutdown).map(identity)

  private def create[F[_]: Async](config: ClusterConfig, persistence: RaftPersistence[F]): F[LiveRaftNode[F]] =
    for
      persisted <- persistence.load
      nowMillis <- Temporal[F].realTime.map(_.toMillis)
      stateRef <- Ref.of[F, RaftRuntimeState](
        RaftRuntimeState(
          currentTerm = persisted.metadata.currentTerm,
          votedFor = persisted.metadata.votedFor,
          role = NodeRole.Follower,
          leaderId = None,
          log = persisted.entries,
          commitIndex = persisted.metadata.commitIndex,
          lastApplied = persisted.metadata.commitIndex,
          materialized = replayCommitted(persisted.entries, persisted.metadata.commitIndex),
          lastHeartbeatMillis = nowMillis
        )
      )
      mutex <- Mutex[F]
      serverRef <- Ref.of[F, Option[ServerSocket]](None)
      fibersRef <- Ref.of[F, List[Fiber[F, Throwable, Unit]]](Nil)
      node = new LiveRaftNode[F](config, persistence, stateRef, mutex, serverRef, fibersRef)
      _ <- node.start
    yield node

  private def replayCommitted(entries: Vector[RaftLogEntry], commitIndex: Long): ServiceState =
    entries.filter(_.index <= commitIndex).sortBy(_.index).foldLeft(ServiceState.empty) { case (state, entry) =>
      LeaseMaterializer.applyCommand(state, entry.command)
    }

private final class LiveRaftNode[F[_]: Async](
    config: ClusterConfig,
    persistence: RaftPersistence[F],
    stateRef: Ref[F, RaftRuntimeState],
    mutex: Mutex[F],
    serverRef: Ref[F, Option[ServerSocket]],
    fibersRef: Ref[F, List[Fiber[F, Throwable, Unit]]]
) extends RaftNode[F]:
  override val nodeId: String = config.nodeId

  private val electionMin = 400.millis
  private val electionMax = 650.millis
  private val heartbeatInterval = 150.millis

  def start: F[Unit] =
    for
      serverFiber <- serverLoop.start
      electionFiber <- electionLoop.start
      heartbeatFiber <- heartbeatLoop.start
      _ <- fibersRef.set(List(serverFiber, electionFiber, heartbeatFiber))
    yield ()

  def shutdown: F[Unit] =
    for
      server <- serverRef.get
      _ <- server.traverse_(socket => Async[F].blocking(socket.close()).handleError(_ => ()))
      fibers <- fibersRef.get
      _ <- fibers.traverse_(_.cancel)
    yield ()

  override def submit(command: ReplicatedCommand): F[Either[NotLeader, StoredResult]] =
    mutex.lock.surround {
      stateRef.get.flatMap { state =>
        if state.role != NodeRole.Leader then leaderHint.map(NotLeader.apply).map(_.asLeft[StoredResult])
        else
          val entry = RaftLogEntry(state.log.size.toLong + 1L, state.currentTerm, command)
          for
            _ <- persistence.appendEntry(entry)
            _ <- stateRef.update(current => current.copy(log = current.log :+ entry))
            afterAppend <- stateRef.get
            replicated <- replicateEntry(afterAppend, entry)
            result <-
              if replicated then commitThrough(entry.index) *> readCommittedResult(command.requestContext)
              else NotLeader(afterAppend.leaderId.flatMap(config.leaderHintEndpoint)).asLeft[StoredResult].pure[F]
          yield result
      }
    }

  override def role: F[NodeRole] = stateRef.get.map(_.role)
  override def leaderHint: F[Option[String]] = stateRef.get.map(_.leaderId.flatMap(config.leaderHintEndpoint))
  override def readState: F[ServiceState] = stateRef.get.map(_.materialized)

  private def serverLoop: F[Unit] =
    Async[F].blocking {
      val server = new ServerSocket()
      server.setReuseAddress(true)
      server.bind(new InetSocketAddress(config.localPeer.host, config.localPeer.port))
      server
    }.bracket { server =>
      serverRef.set(Some(server)) *> acceptLoop(server)
    }(server => serverRef.set(None) *> Async[F].blocking(server.close()).handleError(_ => ()))

  private def acceptLoop(server: ServerSocket): F[Unit] =
    Async[F].blocking(server.accept()).attempt.flatMap {
      case Right(socket) => handleSocket(socket).start.void *> acceptLoop(server)
      case Left(_)       => Async[F].unit
    }

  private def electionLoop: F[Unit] =
    randomElectionTimeout.flatMap(Temporal[F].sleep) *> stateRef.get.flatMap { state =>
      Temporal[F].realTime.map(_.toMillis).flatMap { now =>
        if state.role == NodeRole.Leader || now - state.lastHeartbeatMillis < electionMin.toMillis then electionLoop
        else startElection *> electionLoop
      }
    }

  private def heartbeatLoop: F[Unit] =
    Temporal[F].sleep(heartbeatInterval) *> stateRef.get.flatMap { state =>
      (if state.role == NodeRole.Leader then broadcastHeartbeat(state) else Async[F].unit) *> heartbeatLoop
    }

  private def startElection: F[Unit] =
    mutex.lock.surround {
      for
        candidate <- stateRef.modify { state =>
          val next = state.copy(currentTerm = state.currentTerm + 1L, votedFor = Some(config.nodeId), role = NodeRole.Candidate, leaderId = None)
          next -> next
        }
        _ <- persistence.saveMetadata(PersistedMetadata(candidate.currentTerm, candidate.votedFor, candidate.commitIndex))
        last = candidate.log.lastOption
        votes <- config.raftPeers.filterNot(_.nodeId == config.nodeId).traverse { peer =>
          send(peer, PeerMessage.RequestVote(VoteRequest(candidate.currentTerm, config.nodeId, last.map(_.index).getOrElse(0L), last.map(_.term).getOrElse(0L)))).attempt.flatMap {
            case Right(PeerMessage.RequestVoteAck(response)) =>
              if response.term > candidate.currentTerm then stepDown(response.term, None).as(false)
              else response.voteGranted.pure[F]
            case _ => false.pure[F]
          }
        }
        _ <-
          if 1 + votes.count(identity) >= config.majority then stateRef.update(_.copy(role = NodeRole.Leader, leaderId = Some(config.nodeId)))
          else Async[F].unit
      yield ()
    }

  private def commitThrough(index: Long): F[Unit] =
    for
      updated <- stateRef.modify { state =>
        val entriesToApply = state.log.filter(entry => entry.index > state.lastApplied && entry.index <= index).sortBy(_.index)
        val materialized = entriesToApply.foldLeft(state.materialized) { case (acc, entry) => LeaseMaterializer.applyCommand(acc, entry.command) }
        val next = state.copy(commitIndex = index, lastApplied = index, materialized = materialized)
        next -> next
      }
      _ <- persistence.saveMetadata(PersistedMetadata(updated.currentTerm, updated.votedFor, updated.commitIndex))
      _ <- broadcastHeartbeat(updated)
    yield ()

  private def readCommittedResult(requestContext: RequestContext): F[Either[NotLeader, StoredResult]] =
    stateRef.get.map { state =>
      state.materialized.responses.get((requestContext.tenantId, requestContext.requestId)).map(_.result).toRight(NotLeader(state.leaderId.flatMap(config.leaderHintEndpoint)))
    }

  private def replicateEntry(state: RaftRuntimeState, entry: RaftLogEntry): F[Boolean] =
    config.raftPeers.filterNot(_.nodeId == config.nodeId).traverse { peer =>
      val prev = if entry.index <= 1 then None else state.log.lift((entry.index - 2).toInt)
      val request = AppendEntriesRequest(state.currentTerm, config.nodeId, prev.map(_.index).getOrElse(0L), prev.map(_.term).getOrElse(0L), Vector(entry), state.commitIndex)
      send(peer, PeerMessage.AppendEntries(request)).attempt.flatMap {
        case Right(PeerMessage.AppendEntriesAck(response)) =>
          if response.term > state.currentTerm then stepDown(response.term, Some(peer.nodeId)).as(false)
          else response.success.pure[F]
        case _ => false.pure[F]
      }
    }.map(acks => 1 + acks.count(identity) >= config.majority)

  private def broadcastHeartbeat(state: RaftRuntimeState): F[Unit] =
    config.raftPeers.filterNot(_.nodeId == config.nodeId).traverse_ { peer =>
      val last = state.log.lastOption
      val request = AppendEntriesRequest(state.currentTerm, config.nodeId, last.map(_.index).getOrElse(0L), last.map(_.term).getOrElse(0L), Vector.empty, state.commitIndex)
      send(peer, PeerMessage.AppendEntries(request)).attempt.void
    }

  private def handleSocket(socket: Socket): F[Unit] =
    Async[F].blocking {
      val reader = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8))
      val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream, StandardCharsets.UTF_8))
      (reader, writer)
    }.bracket { case (reader, writer) =>
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
        response <- stateRef.modify { state =>
          if request.term < state.currentTerm then state -> VoteResponse(state.currentTerm, voteGranted = false)
          else
            val termAdjusted = if request.term > state.currentTerm then state.copy(currentTerm = request.term, votedFor = None, role = NodeRole.Follower, leaderId = None) else state
            val localLast = termAdjusted.log.lastOption
            val upToDate = request.lastLogTerm > localLast.map(_.term).getOrElse(0L) ||
              (request.lastLogTerm == localLast.map(_.term).getOrElse(0L) && request.lastLogIndex >= localLast.map(_.index).getOrElse(0L))
            val canVote = termAdjusted.votedFor.forall(_ == request.candidateId)
            val granted = canVote && upToDate
            val next = if granted then termAdjusted.copy(votedFor = Some(request.candidateId), lastHeartbeatMillis = now) else termAdjusted.copy(lastHeartbeatMillis = now)
            next -> VoteResponse(next.currentTerm, granted)
        }
        current <- stateRef.get
        _ <- persistence.saveMetadata(PersistedMetadata(current.currentTerm, current.votedFor, current.commitIndex))
      yield response
    }

  private def handleAppendEntries(request: AppendEntriesRequest): F[AppendEntriesResponse] =
    mutex.lock.surround {
      for
        now <- Temporal[F].realTime.map(_.toMillis)
        response <- stateRef.modify { state =>
          if request.term < state.currentTerm then state -> AppendEntriesResponse(state.currentTerm, success = false, state.log.lastOption.map(_.index).getOrElse(0L))
          else
            val base = state.copy(currentTerm = request.term, role = NodeRole.Follower, leaderId = Some(request.leaderId), votedFor = None, lastHeartbeatMillis = now)
            val prevMatches =
              if request.prevLogIndex == 0 then true
              else base.log.lift((request.prevLogIndex - 1).toInt).exists(_.term == request.prevLogTerm)
            if !prevMatches then base -> AppendEntriesResponse(base.currentTerm, success = false, base.log.lastOption.map(_.index).getOrElse(0L))
            else
              val prefix = base.log.take(request.prevLogIndex.toInt)
              val nextLog = prefix ++ request.entries
              val cappedCommit = math.min(request.leaderCommit, nextLog.lastOption.map(_.index).getOrElse(base.commitIndex))
              val entriesToApply = nextLog.filter(entry => entry.index > base.lastApplied && entry.index <= cappedCommit).sortBy(_.index)
              val materialized = entriesToApply.foldLeft(base.materialized) { case (acc, entry) => LeaseMaterializer.applyCommand(acc, entry.command) }
              val next = base.copy(log = nextLog, commitIndex = cappedCommit, lastApplied = math.max(base.lastApplied, cappedCommit), materialized = materialized)
              next -> AppendEntriesResponse(next.currentTerm, success = true, next.log.lastOption.map(_.index).getOrElse(0L))
        }
        current <- stateRef.get
        _ <- persistence.overwriteEntries(current.log)
        _ <- persistence.saveMetadata(PersistedMetadata(current.currentTerm, current.votedFor, current.commitIndex))
      yield response
    }

  private def stepDown(term: Long, leaderId: Option[String]): F[Unit] =
    for
      now <- Temporal[F].realTime.map(_.toMillis)
      _ <- stateRef.update(_.copy(currentTerm = term, votedFor = None, role = NodeRole.Follower, leaderId = leaderId, lastHeartbeatMillis = now))
      current <- stateRef.get
      _ <- persistence.saveMetadata(PersistedMetadata(current.currentTerm, current.votedFor, current.commitIndex))
    yield ()

  private def send(peer: PeerNode, message: PeerMessage): F[PeerMessage] =
    Async[F].blocking(new Socket()).bracket { socket =>
      Async[F].blocking(socket.connect(new InetSocketAddress(peer.host, peer.port), 1000)).flatMap { _ =>
        Async[F].blocking {
          val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream, StandardCharsets.UTF_8))
          writer.write(message.asJson.noSpaces)
          writer.write("\n")
          writer.flush()
          val reader = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8))
          reader.readLine()
        }.flatMap(line => Async[F].fromEither(decode[PeerMessage](line)))
      }
    }(socket => Async[F].blocking(socket.close()).handleError(_ => ()))

  private def randomElectionTimeout: F[FiniteDuration] =
    Temporal[F].realTime.map(_.toMillis).map { now =>
      val spread = electionMax.toMillis - electionMin.toMillis
      (electionMin.toMillis + (now % (spread + 1L))).millis
    }
