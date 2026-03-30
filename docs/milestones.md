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

## Milestone 2: embedded Raft integration and replicated lease log *(complete)*

Milestone 2 is complete. The repo includes one shared Raft group, replicated mutating lease commands, leader election and `NOT_LEADER` handling, static-config bootstrap, local disk persistence for Raft metadata/log entries, restart replay paths, direct TCP peer RPCs without multiplexing, and automated Scala test coverage for the clustered milestone.

- **Objective:** Introduce embedded Raft and replicate mutating lease commands through one shared group.
- **Deliverables:** Single-group Raft integration, command replication path, explicit follower `NOT_LEADER` behavior for reads and writes, local durable metadata/log persistence, and static-config bootstrap over direct TCP peer endpoints.
- **Out of scope:** Multi-group placement or rebalancing.
- **Validation artifact:** Deterministic failover test output plus a replay transcript from persisted Raft state.
- **Demo/test expectation:** Scripted demo showing leader-mediated mutation, `NOT_LEADER` handling for reads and writes, failover to a new leader, successful replay after restart, static-config bootstrap with explicit node IDs and `IP:port` endpoints, and TCP peer communication without multiplexing.
- **Done means:** Mutating operations commit through one Raft group, leader-only routing remains correct across leader change and process restart, and cluster formation works without DNS or external service discovery.

## Milestone 3: lease state machine with acquire/renew/release/get *(complete)*

Milestone 3 is complete. The repo now includes deterministic replicated apply semantics for `Acquire`, `Renew`, and `Release`, leader-only `GetLease` and `ListLeases` reads in v1, an explicit authoritative materialized lease map keyed by `(tenant_id, resource_id)`, duplicate-request replay through replicated request history, and clustered tests covering expiry, mismatch, replay, and leadership changes.

- **Objective:** Implement the core replicated lease state machine semantics.
- **Deliverables:** Apply logic for acquire, renew, release, get; authoritative materialized lease records; leader-only admission decisions.
- **Out of scope:** Advanced list filtering or broad admin APIs.
- **Validation artifact:** Lease lifecycle matrix plus deterministic state-machine transition test output covering success, expiry, mismatch, duplicate request, and leader-only read behavior.
- **Demo/test expectation:** Deterministic tests show linearizable `GetLease`, leader-only `ListLeases`, renewal before expiry, rejection after authoritative expiry, duplicate request replay across leadership change, and `NOT_LEADER` responses from followers.
- **Done means:** The replicated state machine produces the documented lease lifecycle outcomes under retries and leadership changes, with leader-only reads demonstrated.

## Milestone 4: multitenant quotas, idempotency, and fencing tokens *(complete)*

Milestone 4 is complete. The repo now includes tenant-scoped request deduplication keyed by tenant plus semantic compatibility, per-tenant max-active-lease and max-TTL policy enforcement, monotonic fencing tokens in the external lease view, and explicit principal-to-tenant authorization checks at the service boundary. Deterministic tests cover replay, duplicate mismatch rejection, quota failures, cross-tenant denial, fencing-token turnover, stale-writer rejection, and leader-only read behavior.

- **Objective:** Make multitenancy and stale-writer protection explicit in the service contract.
- **Deliverables:** Tenant-scoped request IDs, quota enforcement, fencing token issuance, and tenant-aware authorization boundaries.
- **Out of scope:** Physical tenant isolation or dedicated tenant shards.
- **Validation artifact:** API error/retry matrix plus idempotency replay transcripts and a fencing-token stale-writer demo.
- **Demo/test expectation:** Scripted demo shows quota failures, idempotent transport retries, duplicate `request_id` rejection across mismatched targets, deterministic replay of original results, and downstream rejection of stale tokens.
- **Done means:** Tenant isolation, dedupe semantics, quotas, and fencing behavior are externally visible and proven by replay and stale-writer rejection tests.

## Milestone 5: persistence, crash recovery, snapshots *(complete)*

Milestone 5 is complete. The repo now persists Raft term/vote/commit/application metadata, writes explicit state-machine snapshots, restores materialized lease state from snapshot plus remaining log suffix, and compacts committed log prefixes after safe local snapshots. Deterministic tests cover restart/replay correctness, dedupe preservation, fencing-token progression, tenant quota reconstruction, snapshot inspection, compaction, and restarted leader/follower behavior using only local disk, local config, and peer TCP connectivity.

- **Objective:** Persist enough state for durable recovery and controlled log growth.
- **Deliverables:** Persisted Raft metadata/log, state-machine snapshots, recovery procedure, and compaction strategy.
- **Out of scope:** Cross-region backup orchestration.
- **Validation artifact:** Restart/replay transcript plus a snapshot inspection checklist and deterministic recovery test output.
- **Demo/test expectation:** Crash and restart tests recover authoritative lease state, dedupe history, and fencing-token progression from snapshot plus log replay, with a snapshot + log replay recovery demonstration that uses only local disk, local config, and peer connectivity.
- **Done means:** Recovery preserves lease safety and idempotency semantics without requiring manual state reconstruction, hidden operator repair steps, or external control-plane dependencies.

## Milestone 6: observability and failure-injection testing *(complete)*

Milestone 6 is complete. The repo now exposes a small in-process observability surface with counters, gauges, recorded timings, and structured events; propagates request-correlation fields through lease admission and replicated command handling; and includes deterministic failure-injection validation for leader loss, persistence-path delay, retries, recovery, quota/auth denials, and local stale-writer validation artifacts.

- **Objective:** Add visibility into correctness and operational behavior.
- **Deliverables:** Metrics, structured logs, trace points, and failure-injection scenarios for leader loss, disk delays, and request retries.
- **Out of scope:** Full production SRE playbooks.
- **Validation artifact:** Failure-injection runbook with expected metrics/log outputs.
- **Demo/test expectation:** Repeatable scenarios show leader loss, retry storms, and delayed disk behavior with observable recovery and bounded unavailability.
- **Done means:** Operators can detect correctness-relevant events and correlate them with failure tests using defined signals.

## Milestone 7: routing, multi-group readiness, and horizontal scale foundations *(complete)*

Milestone 7 is complete. The repo now contains an explicit routing layer, a `GroupId` / `GroupRuntime` boundary, deterministic hash-based placement from `(tenant_id, resource_id)` to logical group ID, a routed service path that preserves the existing client-facing lease API, and local multi-group simulation support. Single-group mode remains the default, but the request path is now explicitly `API -> Router -> GroupRuntime`.

- **Objective:** Introduce routing and placement seams without changing client semantics.
- **Deliverables:** Internal `placement(tenant_id, resource_id) -> raft_group_id` abstraction, routing layer, explicit group runtime boundary, deterministic placement, and shard-compatible list semantics.
- **Out of scope:** Live online rebalancing across many groups.
- **Validation artifact:** Routing contract doc, deterministic routing specs, local multi-group simulation tests, and per-group recovery validation.
- **Demo/test expectation:** Demonstrate that the service resolves placement before reads and writes, can simulate multiple groups locally, keeps API behavior stable, and still relies only on local config, TCP transport, and local process state.
- **Done means:** Routing and placement are explicit implementation seams, deterministic local multi-group execution is test-covered, per-group recovery works, and the v1 transport path remains simple TCP without multiplexing.

## Milestone 8: hardening and benchmark/demo suite *(complete)*

Milestone 8 is complete. The repo now adds explicit clustered startup validation, bounded data-directory ownership checks, a repeatable in-process evaluator for demo and benchmark runs, a manual static-config daemon walkthrough, and reviewer-facing local evaluation guidance for the current v1 boundaries.

- **Objective:** Make the system easier to evaluate, demonstrate, and operate at the end of the v1 milestone train.
- **Deliverables:** Hardening improvements, benchmark/demo suite, and clear local evaluation guidance.
- **Out of scope:** Full production readiness program, multi-region work, and advanced shard management.
- **Validation artifact:** Benchmark/demo report with commands, scenarios, expected outcomes, and known limits.
- **Demo/test expectation:** A reviewer can start the local cluster, run the demo flows, observe the expected correctness and failure behavior, and run a simple repeatable benchmark without hidden setup.
- **Done means:** Tenure has a credible, repeatable local evaluation story and the obvious v1 sharp edges are guarded by explicit validation and startup checks.

## Milestone 9: static multi-group clustered deployment and placement control *(planned)*

- **Objective:** Move from local multi-group simulation to a real clustered multi-group mode with explicit static placement and per-group correctness boundaries.
- **Deliverables:** Static-config multi-group cluster manifests, clustered `GroupRuntime` execution across more than one Raft group, placement resolution for routed reads and writes in clustered mode, tenant-safe cross-group read aggregation where required by the existing API, and per-group recovery/observability coverage.
- **Out of scope:** Live online rebalancing, automatic shard splitting, cross-group transactions, and any control-plane dependency for placement or bootstrap.
- **Validation artifact:** A multi-group cluster runbook plus deterministic test output covering routing, per-group leader loss, restart recovery, and tenant-scoped list behavior without cross-tenant scans.
- **Demo/test expectation:** A reviewer can boot a small static-config cluster with multiple Raft groups, issue lease operations for resources that map to different groups, observe independent per-group leadership and failover, and confirm that routed reads and writes preserve the existing lease API semantics.
- **Done means:** Tenure can run more than one authoritative Raft group in clustered mode with explicit static placement, preserve tenant isolation and fencing semantics across groups, recover each group independently from local disk, and avoid correctness arguments that depend on global scans across all tenants or all resources.

## Milestone 10: controlled rebalancing and shard migration safety *(planned)*

- **Objective:** Introduce explicit placement changes so resources can move between Raft groups without weakening lease safety, tenant isolation, or fencing guarantees.
- **Deliverables:** A versioned placement-map model, an administrative placement-change workflow, resource migration phases with explicit source/target authority boundaries, routed request handling during migration, and recovery rules for interrupted migration.
- **Out of scope:** Fully automatic background balancing, cross-group atomic transactions, elastic membership orchestration, and any design that requires global scans across all tenants or all resources during steady-state operation.
- **Validation artifact:** A migration correctness runbook plus deterministic test output covering move initiation, in-flight request handling, failover during migration, restart recovery, and stale-owner rejection after a completed move.
- **Demo/test expectation:** A reviewer can move a resource range or placement bucket from one group to another in a static cluster, observe the handoff of authoritative ownership, verify that reads and writes route correctly throughout the transition, and confirm that stale actors from the source group are rejected by fencing and placement-version checks.
- **Done means:** Tenure supports operator-directed placement changes between groups with explicit transition states, durable recovery of migration progress, unchanged client-visible lease semantics, and a correctness story that remains bootstrap-safe and independent of external control-plane services.
