package com.richrobertson.tenure.persistence

import cats.effect.Sync
import cats.syntax.all.*
import com.richrobertson.tenure.raft.{PersistedNodeState, PersistedSnapshot}
import com.richrobertson.tenure.service.{LeaseMaterializer, ServiceState}

final case class RecoveredState(
    persisted: PersistedNodeState,
    materialized: ServiceState,
    commitIndex: Long,
    lastApplied: Long
)

object RecoveryBootstrap:
  def recover[F[_]: Sync](persistence: RaftPersistence[F]): F[RecoveredState] =
    persistence.load.flatMap { persisted =>
      val validateSnapshotFormat: F[Unit] =
        persisted.snapshot match
          case Some(snapshot) if snapshot.formatVersion != PersistedSnapshot.formatVersionV1 =>
            Sync[F].raiseError(
              new IllegalStateException(
                s"Unsupported snapshot formatVersion=${snapshot.formatVersion}; expected ${PersistedSnapshot.formatVersionV1}"
              )
            )
          case _ =>
            Sync[F].unit

      validateSnapshotFormat.map { _ =>
        val baseState = persisted.snapshot.map(_.serviceState).getOrElse(ServiceState.empty)
        val baseIndex = persisted.snapshot.map(_.lastIncludedIndex).getOrElse(0L)
        // targetCommitIndex is the authoritative upper bound: max of snapshot index and persisted commitIndex.
        val targetCommitIndex = math.max(baseIndex, persisted.metadata.commitIndex)
        val replayed = persisted.entries
          .filter(entry => entry.index > baseIndex && entry.index <= targetCommitIndex)
          .sortBy(_.index)
          .foldLeft(baseState) { case (state, entry) => LeaseMaterializer.applyCommand(state, entry.command) }

        // After replay, lastApplied == commitIndex by invariant: recovery always applies all committed entries.
        RecoveredState(
          persisted = persisted,
          materialized = replayed,
          commitIndex = targetCommitIndex,
          lastApplied = targetCommitIndex
        )
      }
    }
