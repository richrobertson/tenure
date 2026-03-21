# Milestones

## Milestone 0: repo bootstrap and architecture docs *(complete)*

- **Objective:** Establish a documentation-first repository scaffold and capture the v1 design.
- **Deliverables:** README, architecture spec, API contract, ADR, diagrams, terminology, roadmap, and implementation plan.
- **Out of scope:** Any runnable server or consensus integration.
- **Validation artifact:** A doc review checklist covering architecture, API semantics, milestones, and implementation intent.
- **Demo/test expectation:** A reviewer can walk the docs end to end and answer core questions about lease lifecycle, read consistency, expiration authority, retry behavior, bootstrap-safe runtime assumptions, and the v1 TCP transport choice.
- **Done means:** The v1 contract is reviewable without requiring source code or implied behavior, including its P0 operating model and explicit non-dependencies.

## Milestone 1: local single-node prototype *(complete)*

- **Objective:** Build a minimal local prototype of lease semantics without distributed replication.
- **Deliverables:** In-memory lease state machine, local API surface, deterministic time abstraction, and basic request validation.
- **Completion note:** Complete. The repo now includes a runnable single-node service with a local API surface, in-memory lease state machine, deterministic fake-clock testing, request validation, and tenant-scoped resource identity.
- **Out of scope:** Raft replication, snapshots, and network hardening.
- **Validation artifact:** Deterministic state-machine test suite output plus an API behavior matrix for acquire, renew, release, get, and list.
- **Demo/test expectation:** Scripted demo showing one tenant, multiple resources, fake-clock expiry and renewal boundary tests, rejection of invalid TTLs, tenant-scoped behavior, and local startup of the single-node HTTP prototype without any clustering or DNS assumption.
- **Done means:** A single-node service enforces the documented lease contract with deterministic tests, fake-clock time control, no hidden time dependencies, and a runtime model that does not assume Kubernetes or external discovery.

## Milestone 2: embedded Raft integration and replicated lease log *(in progress)*

Milestone 2 remains in progress in the current prototype. The repo includes one shared Raft group, replicated mutating lease commands, leader election and `NOT_LEADER` handling, static-config bootstrap, local disk persistence for Raft metadata/log entries, restart replay paths, and direct TCP peer RPCs without multiplexing, but milestone completion should not be claimed until the automated test suite is run successfully.

- **Objective:** Introduce embedded Raft and replicate mutating lease commands through one shared group.
- **Deliverables:** Single-group Raft integration, command replication path, explicit follower `NOT_LEADER` behavior for reads and writes, local durable metadata/log persistence, and static-config bootstrap over direct TCP peer endpoints.
- **Out of scope:** Multi-group placement or rebalancing.
- **Validation artifact:** Deterministic failover test output plus a replay transcript from persisted Raft state.
- **Demo/test expectation:** Scripted demo showing leader-mediated mutation, `NOT_LEADER` handling for reads and writes, failover to a new leader, successful replay after restart, static-config bootstrap with explicit node IDs and `IP:port` endpoints, and TCP peer communication without multiplexing.
- **Done means:** Mutating operations commit through one Raft group, leader-only routing remains correct across leader change and process restart, and cluster formation works without DNS or external service discovery.

## Milestone 3: lease state machine with acquire/renew/release/get

- **Objective:** Implement the core replicated lease state machine semantics.
- **Deliverables:** Apply logic for acquire, renew, release, get; authoritative materialized lease records; leader-only admission decisions.
- **Out of scope:** Advanced list filtering or broad admin APIs.
- **Validation artifact:** Lease lifecycle matrix plus deterministic state-machine transition test output covering success, expiry, mismatch, duplicate request, and leader-only read behavior.
- **Demo/test expectation:** Deterministic tests show linearizable `GetLease`, leader-only `ListLeases`, renewal before expiry, rejection after authoritative expiry, and `NOT_LEADER` responses from followers.
- **Done means:** The replicated state machine produces the documented lease lifecycle outcomes under retries and leadership changes, with leader-only reads demonstrated.

## Milestone 4: multitenant quotas, idempotency, and fencing tokens

- **Objective:** Make multitenancy and stale-writer protection explicit in the service contract.
- **Deliverables:** Tenant-scoped request IDs, quota enforcement, fencing token issuance, and tenant-aware authorization boundaries.
- **Out of scope:** Physical tenant isolation or dedicated tenant shards.
- **Validation artifact:** API error/retry matrix plus idempotency replay transcripts and a fencing-token stale-writer demo.
- **Demo/test expectation:** Scripted demo shows quota failures, idempotent transport retries, duplicate `request_id` rejection across mismatched targets, deterministic replay of original results, and downstream rejection of stale tokens.
- **Done means:** Tenant isolation, dedupe semantics, quotas, and fencing behavior are externally visible and proven by replay and stale-writer rejection tests.

## Milestone 5: persistence, crash recovery, snapshots

- **Objective:** Persist enough state for durable recovery and controlled log growth.
- **Deliverables:** Persisted Raft metadata/log, state-machine snapshots, recovery procedure, and compaction strategy.
- **Out of scope:** Cross-region backup orchestration.
- **Validation artifact:** Restart/replay transcript plus a snapshot inspection checklist and deterministic recovery test output.
- **Demo/test expectation:** Crash and restart tests recover authoritative lease state, dedupe history, and fencing-token progression from snapshot plus log replay, with a snapshot + log replay recovery demonstration that uses only local disk, local config, and peer connectivity.
- **Done means:** Recovery preserves lease safety and idempotency semantics without requiring manual state reconstruction, hidden operator repair steps, or external control-plane dependencies.

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
- **Demo/test expectation:** Demonstrate that the service resolves placement before reads and writes while still targeting one shared group in v1, and that client/peer correctness does not depend on Kubernetes, DNS, or external service discovery.
- **Done means:** Routing and placement are explicit implementation seams, the client contract still matches the single-group behavior, and the v1 transport path remains simple TCP without multiplexing.

## Milestone 8: hardening and benchmark/demo suite

- **Objective:** Prepare a realistic demonstration of correctness and performance boundaries.
- **Deliverables:** Benchmark plan, repeatable demo scenarios, limit documentation, and hardening notes for operational readiness gaps.
- **Out of scope:** Claiming production readiness.
- **Validation artifact:** Benchmark/demo report with scenarios, methodology, known limits, and links to the scripted demos from earlier milestones.
- **Demo/test expectation:** Present a repeatable suite covering throughput, failover behavior, leader-only reads, recovery latency, fencing rejection, and remaining risks.
- **Done means:** The repository can demonstrate what v1 proves, what it does not prove, and where the next engineering investment belongs, using repeatable artifacts rather than implied behavior.
