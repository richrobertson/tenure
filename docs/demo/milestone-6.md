# Milestone 6 demo: observability and failure injection

This runbook documents the narrow Milestone 6 surface for the v1 single-group replicated lease service.

## What Milestone 6 adds

- local in-process metrics for leadership transitions, leader changes, mutating outcomes, `NOT_LEADER` responses, duplicate retries, quota/auth denials, recovery, snapshots, commit latency, and failure-injection delays
- structured log events with stable `event_type` names and correlation fields such as `node_id`, `tenant_id`, `resource_id`, `request_id`, `term`, and `leader_id`
- a local debug endpoint at `GET /debug/observability`
- deterministic failure-injection coverage for leader loss, disk delays on the persistence path, and request retries / retry storms

## Expected signals

### Leader loss

Trigger:
1. Start a three-node cluster.
2. Commit an acquire on the current leader.
3. Stop that leader.
4. Retry reads on a follower during the gap.
5. Wait for a replacement leader and submit a new mutating request.

Expected metrics/logs:
- `leader_changes_total{node_id=...}` increments.
- `leadership_transitions_total{role=leader|follower}` increments.
- `not_leader_responses_total{operation_kind=get|write}` increments during the handoff window.
- structured logs include `event_type=role.transition`, `event_type=election.started`, and `event_type=lease.request.result` with `error_code=NOT_LEADER`.

### Disk delay

Trigger:
1. Inject a delay at `persistence_append`.
2. Submit mutating requests until a snapshot is produced.
3. Clear the delay and restart the node from the same data directory.

Expected metrics/logs:
- `failure_injection_total{point=persistence_append}` increments.
- `failure_injection_delay_ms{point=persistence_append}` records the configured delay.
- `commit_latency_ms` contains delayed samples.
- `snapshot_events_total{event=saved}` increments.
- structured logs include `event_type=failure_injection.delay`, `event_type=snapshot.saved`, and `event_type=recovery.completed` after restart.

### Retry storm

Trigger:
1. Submit an acquire.
2. Replay the same logical acquire using the same `request_id`.
3. Submit quota-denied and auth-denied requests.
4. Optionally simulate a downstream stale-writer rejection after fencing-token turnover.

Expected metrics/logs:
- `duplicate_requests_total` increments for exact retry replay.
- `quota_rejections_total` increments for quota denial.
- `auth_denials_total` increments for tenant authorization failure.
- `stale_writer_rejections_total` is available for local downstream validation artifacts.
- structured logs include `event_type=lease.request.result` and `event_type=stale_writer.rejected`.

## Deterministic validation command

Run the Milestone 6 validation spec:

```bash
sbt "testOnly com.richrobertson.tenure.observability.ObservabilityFailureInjectionSpec"
```

The spec exercises:
- leader loss with visible role transition and `NOT_LEADER` signals
- request retry replay plus quota/auth denial visibility
- persistence-path delay injection plus snapshot and restart recovery visibility

## Local inspection

When running the daemon locally, inspect the current in-process snapshot with:

```bash
curl -s http://127.0.0.1:<api-port>/debug/observability
```

The endpoint returns counters, gauges, recorded timings, and the structured event log buffer so a reviewer can correlate the scenario with the expected signals.

## Still out of scope

Milestone 6 stays local-first and dependency-light. It does **not** add hosted telemetry backends, dashboards, alerts, or a full SRE operating playbook.
