/**
 * File-backed persistence for the Raft runtime.
 *
 * This package owns the local durability boundary for Raft metadata, log entries, snapshots, and the node
 * ownership marker used to prevent accidental data-directory reuse under the wrong identity.
 */
package com.richrobertson.tenure.persistence

import cats.effect.Sync
import cats.syntax.all.*
import com.richrobertson.tenure.observability.Observability
import com.richrobertson.tenure.raft.{PersistedMetadata, PersistedNodeState, PersistedSnapshot, RaftLogEntry}
import com.richrobertson.tenure.testkit.{FailureInjector, FailurePoint}
import com.richrobertson.tenure.service.ServiceState
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/** Persistence interface required by the Raft runtime and recovery bootstrap. */
trait RaftPersistence[F[_]]:
  /** Loads the complete persisted node state. */
  def load: F[PersistedNodeState]
  /** Persists Raft metadata such as term, vote, commit index, and last applied. */
  def saveMetadata(metadata: PersistedMetadata): F[Unit]
  /** Appends one durable log entry. */
  def appendEntry(entry: RaftLogEntry): F[Unit]
  /** Rewrites the durable log with a new set of entries. */
  def overwriteEntries(entries: Vector[RaftLogEntry]): F[Unit]
  /** Loads the latest persisted snapshot, if any. */
  def loadSnapshot: F[Option[PersistedSnapshot]]
  /** Persists one snapshot. */
  def saveSnapshot(snapshot: PersistedSnapshot): F[Unit]

/** Constructors for [[RaftPersistence]]. */
object RaftPersistence:
  /** Creates a file-backed persistence layer using default node identity and no-op helpers. */
  def fileBacked[F[_]: Sync](dataDir: String)(using Codec[PersistedNodeState], Codec[PersistedMetadata], Codec[RaftLogEntry], Codec[PersistedSnapshot], Codec[ServiceState]): F[RaftPersistence[F]] =
    fileBacked(dataDir, "local", FailureInjector.noop[F], Observability.noop[F])

  /** Creates a file-backed persistence layer with a caller-supplied node ID and observability. */
  def fileBacked[F[_]: Sync](
      dataDir: String,
      nodeId: String,
      observability: Observability[F]
  )(using Codec[PersistedNodeState], Codec[PersistedMetadata], Codec[RaftLogEntry], Codec[PersistedSnapshot], Codec[ServiceState]): F[RaftPersistence[F]] =
    fileBacked(dataDir, nodeId, FailureInjector.noop[F], observability)

  /**
   * Creates a file-backed persistence layer with explicit failure injection and observability.
   *
   * This is the main constructor used by the real daemon and local evaluation harness.
   */
  def fileBacked[F[_]: Sync](
      dataDir: String,
      nodeId: String,
      failureInjector: FailureInjector[F],
      observability: Observability[F]
  )(using Codec[PersistedNodeState], Codec[PersistedMetadata], Codec[RaftLogEntry], Codec[PersistedSnapshot], Codec[ServiceState]): F[RaftPersistence[F]] =
    Sync[F].blocking {
      val root = Paths.get(dataDir)
      PersistenceLayout.prepare(root, nodeId)
      new FileBackedRaftPersistence[F](root, nodeId, failureInjector, observability)
    }

/** Concrete newline-delimited JSON persistence implementation. */
private final class FileBackedRaftPersistence[F[_]: Sync](root: Path, nodeId: String, failureInjector: FailureInjector[F], observability: Observability[F])(using Codec[PersistedNodeState], Codec[PersistedMetadata], Codec[RaftLogEntry], Codec[PersistedSnapshot], Codec[ServiceState]) extends RaftPersistence[F]:
  private val metadataPath = root.resolve("metadata.json")
  private val logPath = root.resolve("log.jsonl")
  private val snapshotPath = root.resolve("snapshot.json")

  override def load: F[PersistedNodeState] =
    for
      _ <- ensureFiles
      metadata <- readMetadata
      entries <- readEntries
      snapshot <- readSnapshot
    yield PersistedNodeState(metadata, snapshot, entries)

  override def saveMetadata(metadata: PersistedMetadata): F[Unit] =
    delay(FailurePoint.PersistenceMetadataSave) *> writeString(metadataPath, metadata.asJson.spaces2)

  override def appendEntry(entry: RaftLogEntry): F[Unit] =
    delay(FailurePoint.PersistenceAppend) *> Sync[F].blocking {
      Files.writeString(
        logPath,
        entry.asJson.noSpaces + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      ()
    }

  override def overwriteEntries(entries: Vector[RaftLogEntry]): F[Unit] =
    delay(FailurePoint.PersistenceOverwrite) *> writeString(logPath, entries.map(_.asJson.noSpaces).mkString("", "\n", if entries.isEmpty then "" else "\n"))

  override def loadSnapshot: F[Option[PersistedSnapshot]] =
    for
      _ <- ensureFiles
      snapshot <- readSnapshot
    yield snapshot

  override def saveSnapshot(snapshot: PersistedSnapshot): F[Unit] =
    delay(FailurePoint.PersistenceSnapshotSave) *> writeString(snapshotPath, snapshot.asJson.spaces2)

  /** Ensures the managed files exist before reads or writes proceed. */
  private def ensureFiles: F[Unit] =
    Sync[F].blocking {
      PersistenceLayout.prepare(root, nodeId)
      if !Files.exists(metadataPath) then Files.writeString(metadataPath, PersistedMetadata.initial.asJson.spaces2, StandardCharsets.UTF_8)
      if !Files.exists(logPath) then Files.writeString(logPath, "", StandardCharsets.UTF_8)
      if !Files.exists(snapshotPath) then Files.writeString(snapshotPath, "", StandardCharsets.UTF_8)
    }

  /** Reads metadata from `metadata.json`. */
  private def readMetadata: F[PersistedMetadata] =
    Sync[F].blocking(Files.readString(metadataPath, StandardCharsets.UTF_8)).flatMap { raw =>
      Sync[F].fromEither(decode[PersistedMetadata](raw))
    }

  /** Reads all persisted log entries from `log.jsonl`. */
  private def readEntries: F[Vector[RaftLogEntry]] =
    Sync[F].blocking(Files.readAllLines(logPath, StandardCharsets.UTF_8)).flatMap { lines =>
      lines.toArray.toVector.map(_.toString.trim).filter(_.nonEmpty).traverse(line => Sync[F].fromEither(decode[RaftLogEntry](line)))
    }

  /** Reads the latest snapshot, interpreting an empty file as "no snapshot". */
  private def readSnapshot: F[Option[PersistedSnapshot]] =
    Sync[F].blocking(Files.readString(snapshotPath, StandardCharsets.UTF_8)).flatMap { raw =>
      val content = raw.trim
      if content.isEmpty then Sync[F].pure(None)
      else Sync[F].fromEither(decode[PersistedSnapshot](content)).map(Some.apply)
    }

  /** Overwrites one managed file atomically from the caller's perspective. */
  private def writeString(path: Path, value: String): F[Unit] =
    Sync[F].blocking {
      Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
      ()
    }

  /** Applies any configured delay/failure injection for a persistence operation. */
  private def delay(point: FailurePoint): F[Unit] = failureInjector.inject(point, nodeId)

/** Managed file-layout rules for a node data directory. */
private object PersistenceLayout:
  private val markerFileName = "node-id"
  private val managedFiles = List("metadata.json", "log.jsonl", "snapshot.json", markerFileName)

  /**
   * Validates and prepares the persistence directory.
   *
   * This method enforces node ownership via the `node-id` marker, ensures the directory is writable, and
   * rejects directory/file shape conflicts for the managed files.
   */
  def prepare(root: Path, nodeId: String): Unit =
    val trimmedNodeId = nodeId.trim
    if trimmedNodeId.isEmpty then
      throw new IllegalArgumentException("nodeId must be non-empty before preparing persistence")
    if nodeId != trimmedNodeId then
      throw new IllegalArgumentException("nodeId must not contain leading or trailing whitespace before preparing persistence")

    if Files.exists(root) && !Files.isDirectory(root) then
      throw new IllegalArgumentException(s"data directory must be a directory path, found file: $root")

    Files.createDirectories(root)

    if !Files.isWritable(root) then
      throw new IllegalArgumentException(s"data directory must be writable: $root")

    managedFiles.foreach { fileName =>
      val path = root.resolve(fileName)
      if Files.exists(path) && Files.isDirectory(path) then
        throw new IllegalArgumentException(s"persisted path must be a file, found directory: $path")
    }

    val markerPath = root.resolve(markerFileName)
    if Files.exists(markerPath) then
      val storedNodeId = Files.readString(markerPath, StandardCharsets.UTF_8).trim
      if storedNodeId.isEmpty then
        throw new IllegalArgumentException(
          s"data directory $root has an empty '$markerFileName' marker; please fix or remove the directory before reuse"
        )
      else if storedNodeId != trimmedNodeId then
        throw new IllegalArgumentException(
          s"data directory $root belongs to node '$storedNodeId', not '$trimmedNodeId'"
        )
    else
      Files.writeString(markerPath, trimmedNodeId + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
