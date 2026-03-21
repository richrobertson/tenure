# Milestone 5 demo: persistence, crash recovery, snapshots

This demo documents the narrow Milestone 5 recovery path for the single shared Raft group used in v1.

## What this milestone adds

- Local-disk persistence for Raft `currentTerm`, `votedFor`, `commitIndex`, and `lastApplied`.
- Local-disk persistence for the replicated log suffix needed after the latest safe snapshot.
- Deterministic state-machine snapshots that capture authoritative lease state, request dedupe history, fencing-token progression, and quota-relevant materialized state.
- Recovery order that is explicit in code and test coverage:
  1. load persisted Raft metadata
  2. load the latest snapshot if present
  3. replay the remaining committed log suffix
  4. restore the authoritative materialized service state
- Snapshot-driven log compaction after a committed threshold is reached.

## Commands

```bash
sbt test
sbt "testOnly com.richrobertson.tenure.service.RaftIntegrationSpec"
```

## Restart and replay transcript

The automated recovery spec exercises the following sequence using only local temp directories, local config, and peer TCP sockets:

1. Start a three-node cluster with explicit node IDs and `127.0.0.1:port` peer endpoints.
2. Elect one leader and verify a follower rejects leader-only reads.
3. Commit enough mutating commands to force a local snapshot and log compaction.
4. Stop one node, restart it from the same local data directory, and confirm it reconstructs the same authoritative lease state from snapshot + log suffix.
5. Verify replay-preserved request dedupe by reissuing an already-committed request ID and receiving the original result instead of a second mutation.
6. Verify fencing tokens remain monotonic across restart and replay.
7. Confirm followers still reject leader-only reads after restart while the elected leader continues serving authoritative reads.

## Snapshot inspection checklist

A valid Milestone 5 snapshot should let a reviewer confirm all of the following from local disk artifacts:

- Snapshot file exists under the node data directory.
- Snapshot contains an explicit format version.
- Snapshot records `lastIncludedIndex` and `lastIncludedTerm`.
- Snapshot captures materialized lease records keyed by `(tenant_id, resource_id)`.
- Snapshot captures dedupe history keyed by `(tenant_id, request_id)`.
- Snapshot preserves fencing-token progression because the materialized lease records retain the latest fencing token per resource.
- Snapshot preserves quota-relevant active/released/expired lease state needed for deterministic replay and admission decisions.
- Log compaction keeps only entries after `lastIncludedIndex`.
- Restart can reconstruct the same authoritative state from `snapshot + remaining log` as from full replay.

## Scope reminder

This milestone does **not** add cross-region backups, cloud storage orchestration, or streaming install-snapshot behavior. It keeps recovery boring, local, and auditable for the v1 single-group design.
