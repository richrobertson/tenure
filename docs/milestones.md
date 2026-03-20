# Milestones

## Milestone 0: repo bootstrap and architecture docs

- **Objective:** Establish a documentation-first repository scaffold and capture the v1 design.
- **Deliverables:** README, architecture spec, API contract, ADR, diagrams, terminology, roadmap, and implementation plan.
- **Out of scope:** Any runnable server or consensus integration.
- **Validation artifact:** A doc review checklist covering architecture, API semantics, milestones, and implementation intent.
- **Demo/test expectation:** A reviewer can walk the docs end to end and answer core questions about lease lifecycle, read consistency, expiration authority, and retry behavior.
- **Done means:** The v1 contract is reviewable without requiring source code or implied behavior.

## Milestone 1: local single-node prototype

- **Objective:** Build a minimal local prototype of lease semantics without distributed replication.
- **Deliverables:** In-memory lease state machine, local API surface, deterministic time abstraction, and basic request validation.
- **Out of scope:** Raft replication, snapshots, and network hardening.
- **Validation artifact:** State-machine test suite and API behavior matrix for acquire, renew, release, get, and list.
- **Demo/test expectation:** Scripted demo showing one tenant, multiple resources, renewal-before-expiry, and rejection of invalid TTLs or duplicate request IDs.
- **Done means:** A single-node service enforces the documented lease contract with deterministic tests and no hidden time dependencies.

## Milestone 2: embedded Raft integration and replicated lease log

- **Objective:** Introduce embedded Raft and replicate mutating lease commands through one shared group.
- **Deliverables:** Single-group Raft integration, command replication path, leader forwarding/redirection behavior, and local durable log persistence.
- **Out of scope:** Multi-group placement or rebalancing.
- **Validation artifact:** Deterministic failover test and replay transcript from persisted Raft state.
- **Demo/test expectation:** Scripted demo showing leader-mediated mutation, `NOT_LEADER` handling, failover to a new leader, and successful replay after restart.
- **Done means:** Mutating operations commit through one Raft group and remain correct across leader change and process restart.

## Milestone 3: lease state machine with acquire/renew/release/get

- **Objective:** Implement the core replicated lease state machine semantics.
- **Deliverables:** Apply logic for acquire, renew, release, get; authoritative materialized lease records; leader-only admission decisions.
- **Out of scope:** Advanced list filtering or broad admin APIs.
- **Validation artifact:** Lease lifecycle matrix covering success, expiry, mismatch, duplicate request, and leader-only read behavior.
- **Demo/test expectation:** Deterministic tests show linearizable `GetLease`, leader-only `ListLeases`, renewal before expiry, and rejection after authoritative expiry.
- **Done means:** The replicated state machine produces the documented lease lifecycle outcomes under retries and leadership changes.

## Milestone 4: multitenant quotas, idempotency, and fencing tokens

- **Objective:** Make multitenancy and stale-writer protection explicit in the service contract.
- **Deliverables:** Tenant-scoped request IDs, quota enforcement, fencing token issuance, and tenant-aware authorization boundaries.
- **Out of scope:** Physical tenant isolation or dedicated tenant shards.
- **Validation artifact:** API error/retry matrix plus a fencing-token stale-writer demo.
- **Demo/test expectation:** Scripted demo shows quota failures, idempotent transport retries, duplicate `request_id` rejection across mismatched targets, and downstream rejection of stale tokens.
- **Done means:** Tenant isolation, dedupe semantics, quotas, and fencing behavior are externally visible and test-proven.

## Milestone 5: persistence, crash recovery, snapshots

- **Objective:** Persist enough state for durable recovery and controlled log growth.
- **Deliverables:** Persisted Raft metadata/log, state-machine snapshots, recovery procedure, and compaction strategy.
- **Out of scope:** Cross-region backup orchestration.
- **Validation artifact:** Restart/replay transcript plus a snapshot inspection checklist.
- **Demo/test expectation:** Crash and restart tests recover authoritative lease state, dedupe history, and fencing-token progression from snapshot plus log replay.
- **Done means:** Recovery preserves lease safety and idempotency semantics without requiring manual state reconstruction.

## Milestone 6: observability and failure-injection testing

- **Objective:** Add visibility into correctness and operational behavior.
- **Deliverables:** Metrics, structured logs, trace points, and failure-injection scenarios for leader loss, disk delays, and request retries.
- **Out of scope:** Full production SRE playbooks.
- **Validation artifact:** Failure-injection runbook with expected metrics/log outputs.
- **Demo/test expectation:** Repeatable scenarios show leader loss, retry storms, and delayed disk behavior with observable recovery and bounded unavailability.
- **Done means:** Operators can detect correctness-relevant events and correlate them with failure tests using defined signals.

## Milestone 7: sharding-ready routing abstraction

- **Objective:** Introduce routing and placement seams without changing client semantics.
- **Deliverables:** Internal `placement(tenant_id, resource_id) -> raft_group_id` abstraction, routing layer, and shard-compatible list semantics.
- **Out of scope:** Live online rebalancing across many groups.
- **Validation artifact:** Routing contract doc and request-flow demo through the placement API.
- **Demo/test expectation:** Demonstrate that the service resolves placement before reads and writes while still targeting one shared group in v1.
- **Done means:** Routing and placement are explicit implementation seams, and the client contract still matches the single-group behavior.

## Milestone 8: hardening and benchmark/demo suite

- **Objective:** Prepare a realistic demonstration of correctness and performance boundaries.
- **Deliverables:** Benchmark plan, repeatable demo scenarios, limit documentation, and hardening notes for operational readiness gaps.
- **Out of scope:** Claiming production readiness.
- **Validation artifact:** Benchmark/demo report with scenarios, methodology, and known limits.
- **Demo/test expectation:** Present a repeatable suite covering throughput, failover behavior, leader-only reads, recovery latency, and remaining risks.
- **Done means:** The repository can demonstrate what v1 proves, what it does not prove, and where the next engineering investment belongs.
