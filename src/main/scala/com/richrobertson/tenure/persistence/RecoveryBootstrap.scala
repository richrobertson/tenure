package com.richrobertson.tenure.persistence

import cats.effect.Sync
import cats.syntax.all.*
import com.richrobertson.tenure.raft.PersistedNodeState
import com.richrobertson.tenure.service.{LeaseMaterializer, ServiceState}

final case class RecoveredState(
    persisted: PersistedNodeState,
    materialized: ServiceState,
    commitIndex: Long,
    lastApplied: Long
)

object RecoveryBootstrap:
  def recover[F[_]: Sync](persistence: RaftPersistence[F]): F[RecoveredState] =
    persistence.load.map { persisted =>
      val baseState = persisted.snapshot.map(_.serviceState).getOrElse(ServiceState.empty)
      val baseIndex = persisted.snapshot.map(_.lastIncludedIndex).getOrElse(0L)
      val targetCommitIndex = math.max(baseIndex, persisted.metadata.commitIndex)
      val replayed = persisted.entries
        .filter(entry => entry.index > baseIndex && entry.index <= targetCommitIndex)
        .sortBy(_.index)
        .foldLeft(baseState) { case (state, entry) => LeaseMaterializer.applyCommand(state, entry.command) }

      RecoveredState(
        persisted = persisted,
        materialized = replayed,
        commitIndex = targetCommitIndex,
        lastApplied = targetCommitIndex
      )
    }
