package com.richrobertson.tenure.persistence

import cats.effect.Sync
import cats.syntax.all.*
import com.richrobertson.tenure.raft.{PersistedMetadata, PersistedNodeState, RaftLogEntry}
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

trait RaftPersistence[F[_]]:
  def load: F[PersistedNodeState]
  def saveMetadata(metadata: PersistedMetadata): F[Unit]
  def appendEntry(entry: RaftLogEntry): F[Unit]
  def overwriteEntries(entries: Vector[RaftLogEntry]): F[Unit]

object RaftPersistence:
  def fileBacked[F[_]: Sync](dataDir: String)(using Codec[PersistedNodeState], Codec[PersistedMetadata], Codec[RaftLogEntry]): F[RaftPersistence[F]] =
    Sync[F].delay {
      val root = Paths.get(dataDir)
      Files.createDirectories(root)
      new FileBackedRaftPersistence[F](root)
    }

private final class FileBackedRaftPersistence[F[_]: Sync](root: Path)(using Codec[PersistedNodeState], Codec[PersistedMetadata], Codec[RaftLogEntry]) extends RaftPersistence[F]:
  private val metadataPath = root.resolve("metadata.json")
  private val logPath = root.resolve("log.jsonl")

  override def load: F[PersistedNodeState] =
    for
      _ <- ensureFiles
      metadata <- readMetadata
      entries <- readEntries
    yield PersistedNodeState(metadata, entries)

  override def saveMetadata(metadata: PersistedMetadata): F[Unit] =
    writeString(metadataPath, metadata.asJson.spaces2)

  override def appendEntry(entry: RaftLogEntry): F[Unit] =
    Sync[F].blocking {
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
    writeString(logPath, entries.map(_.asJson.noSpaces).mkString("", "\n", if entries.isEmpty then "" else "\n"))

  private def ensureFiles: F[Unit] =
    Sync[F].blocking {
      Files.createDirectories(root)
      if !Files.exists(metadataPath) then Files.writeString(metadataPath, PersistedMetadata.initial.asJson.spaces2, StandardCharsets.UTF_8)
      if !Files.exists(logPath) then Files.writeString(logPath, "", StandardCharsets.UTF_8)
    }

  private def readMetadata: F[PersistedMetadata] =
    Sync[F].blocking(Files.readString(metadataPath, StandardCharsets.UTF_8)).flatMap { raw =>
      Sync[F].fromEither(decode[PersistedMetadata](raw))
    }

  private def readEntries: F[Vector[RaftLogEntry]] =
    Sync[F].blocking(Files.readAllLines(logPath, StandardCharsets.UTF_8)).flatMap { lines =>
      lines.toArray.toVector.map(_.toString.trim).filter(_.nonEmpty).traverse(line => Sync[F].fromEither(decode[RaftLogEntry](line)))
    }

  private def writeString(path: Path, value: String): F[Unit] =
    Sync[F].blocking {
      Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
      ()
    }
