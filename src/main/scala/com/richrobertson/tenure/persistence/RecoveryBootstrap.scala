/**
 * Restart recovery from persisted Raft state.
 *
 * Recovery reconstructs materialized lease state from the latest snapshot plus replay of committed log
 * entries. It does not consult any external system.
 */
package com.richrobertson.tenure.persistence

import cats.effect.Sync
import cats.syntax.all.*
import com.richrobertson.tenure.observability.{LogEvent, Observability}
import com.richrobertson.tenure.raft.{PersistedNodeState, PersistedSnapshot}
import com.richrobertson.tenure.service.{LeaseMaterializer, ServiceState}

/** Result of replaying persisted state during startup. */
final case class RecoveredState(
    persisted: PersistedNodeState,
    materialized: ServiceState,
    commitIndex: Long,
    lastApplied: Long
)

/** Recovery entry points used by the Raft runtime during startup. */
object RecoveryBootstrap:
  /** Recovers persisted state with default node identity and no-op observability. */
  def recover[F[_]: Sync](persistence: RaftPersistence[F]): F[RecoveredState] =
    recover(persistence, "local", Observability.noop[F], Sync[F].pure(0L))

  /**
   * Recovers materialized state from persistence.
   *
   * The recovered `commitIndex` is the maximum of the snapshot index and persisted metadata commit index,
   * and `lastApplied` is set to that same value after replay.
   */
  def recover[F[_]: Sync](persistence: RaftPersistence[F], nodeId: String, observability: Observability[F], nowMillis: F[Long]): F[RecoveredState] =
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

      validateSnapshotFormat.flatMap { _ =>
        val baseState = persisted.snapshot.map(_.serviceState).getOrElse(ServiceState.empty)
        val baseIndex = persisted.snapshot.map(_.lastIncludedIndex).getOrElse(0L)
        // targetCommitIndex is the authoritative upper bound: max of snapshot index and persisted commitIndex.
        val targetCommitIndex = math.max(baseIndex, persisted.metadata.commitIndex)
        val replayed = persisted.entries
          .filter(entry => entry.index > baseIndex && entry.index <= targetCommitIndex)
          .sortBy(_.index)
          .foldLeft(baseState) { case (state, entry) => LeaseMaterializer.applyCommand(state, entry.command) }

        // After replay, lastApplied == commitIndex by invariant: recovery always applies all committed entries.
        val recovered = RecoveredState(
          persisted = persisted,
          materialized = replayed,
          commitIndex = targetCommitIndex,
          lastApplied = targetCommitIndex
        )
        nowMillis.flatMap(ts => observability.incrementCounter("recovery_events_total", Map("node_id" -> nodeId)) *> observability.log(LogEvent(ts, "INFO", "recovery.completed", "node recovered persisted state", nodeId = Some(nodeId), fields = Map("commit_index" -> targetCommitIndex.toString, "snapshot_present" -> persisted.snapshot.nonEmpty.toString, "log_entries" -> persisted.entries.size.toString))).as(recovered))
      }
    }
