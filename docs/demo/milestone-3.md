# Milestone 3 demo and validation

Milestone 3 completes the replicated lease lifecycle on top of the shared Raft log from Milestone 2.

## What this milestone proves

- `Acquire`, `Renew`, and `Release` are admitted by the leader and applied from committed log entries.
- `GetLease` reads from the authoritative materialized lease map reconstructed from committed commands.
- Followers reject both reads and writes with `NOT_LEADER` in v1.
- Expiry, holder mismatch, lease-id mismatch, duplicate `request_id`, leadership change, and replay behavior are covered by deterministic tests.

## Lease lifecycle matrix

| Scenario | Validation path |
| --- | --- |
| Acquire on unleased resource succeeds | `LeaseStateMachineSpec`, `LeaseServiceSpec` |
| Second acquire during active lease fails | `LeaseStateMachineSpec` |
| Acquire after authoritative expiry succeeds | `LeaseStateMachineSpec`, `LeaseServiceSpec` |
| Renew before expiry succeeds for correct holder and lease | `LeaseStateMachineSpec`, `LeaseServiceSpec` |
| Renew after authoritative expiry fails | `LeaseStateMachineSpec`, `LeaseServiceSpec` |
| Renew with wrong holder or wrong lease ID fails | `LeaseStateMachineSpec`, `LeaseServiceSpec` |
| Release succeeds for the correct holder | `LeaseStateMachineSpec`, `LeaseServiceSpec` |
| Release with wrong holder fails | `LeaseStateMachineSpec`, `LeaseServiceSpec` |
| `GetLease` returns committed authoritative state | `LeaseServiceSpec`, `RaftIntegrationSpec` |
| Multi-node failover and restart preserve leader-only authority and follower catch-up | `RaftIntegrationSpec` |
| Follower read or write returns `NOT_LEADER` | `LeaseServiceSpec` |
| Duplicate `request_id` replays deterministically after leader change | `LeaseServiceSpec` |
| Restart or replay reconstructs the same materialized lease view | `RaftIntegrationSpec` |

## Commands

Run the full automated suite:

```bash
sbt test
```

Run only the Milestone 3-focused suites:

```bash
sbt "testOnly com.richrobertson.tenure.statemachine.LeaseStateMachineSpec com.richrobertson.tenure.service.LeaseServiceSpec com.richrobertson.tenure.service.RaftIntegrationSpec"
```

## Notes

- The authoritative lease map remains keyed by `(tenant_id, resource_id)`.
- `RaftIntegrationSpec` now restores a real three-node cluster check for leader-only follower rejection, failover to a new leader, and restart catch-up of the old leader from persisted Raft state.
- v1 still uses one shared Raft group and leader-only reads.
- Advanced list filtering, quota enforcement, and broader admin/query surfaces remain out of scope until later milestones.
