# Milestones

## Milestone 0: repo bootstrap and architecture docs

- **Objective:** Establish a documentation-first repository scaffold and capture the v1 design.
- **Deliverables:** README, architecture spec, API contract, ADR, diagrams, terminology, and roadmap.
- **Out of scope:** Any runnable server or consensus integration.
- **Validation/demo criteria:** A reviewer can understand the problem, architecture, and implementation plan from docs alone.

## Milestone 1: local single-node prototype

- **Objective:** Build a minimal local prototype of lease semantics without distributed replication.
- **Deliverables:** In-memory lease state machine, local API surface, deterministic time abstraction, and basic request validation.
- **Out of scope:** Raft replication, snapshots, and network hardening.
- **Validation/demo criteria:** Demonstrate acquire, renew, release, and get behavior for one tenant and multiple resources.

## Milestone 2: embedded Raft integration and replicated lease log

- **Objective:** Introduce embedded Raft and replicate mutating lease commands through one shared group.
- **Deliverables:** Single-group Raft integration, command replication path, leader forwarding/redirection behavior, and local durable log persistence.
- **Out of scope:** Multi-group placement or rebalancing.
- **Validation/demo criteria:** Show leader-mediated mutation with successful failover and replay from persisted Raft state.

## Milestone 3: lease state machine with acquire/renew/release/get

- **Objective:** Implement the core replicated lease state machine semantics.
- **Deliverables:** Apply logic for acquire, renew, release, get; authoritative materialized lease records; leader-only admission decisions.
- **Out of scope:** Advanced list filtering or broad admin APIs.
- **Validation/demo criteria:** Demonstrate linearizable lease lifecycle behavior under retries and leadership changes.

## Milestone 4: multitenant quotas, idempotency, and fencing tokens

- **Objective:** Make multitenancy and stale-writer protection explicit in the service contract.
- **Deliverables:** Tenant-scoped request IDs, quota enforcement, fencing token issuance, and tenant-aware authorization boundaries.
- **Out of scope:** Physical tenant isolation or dedicated tenant shards.
- **Validation/demo criteria:** Demonstrate quota failures, idempotent retries, and stale client rejection using fencing tokens.

## Milestone 5: persistence, crash recovery, snapshots

- **Objective:** Persist enough state for durable recovery and controlled log growth.
- **Deliverables:** Persisted Raft metadata/log, state-machine snapshots, recovery procedure, and compaction strategy.
- **Out of scope:** Cross-region backup orchestration.
- **Validation/demo criteria:** Restart nodes after crashes and recover authoritative lease state without violating lease safety.

## Milestone 6: observability and failure-injection testing

- **Objective:** Add visibility into correctness and operational behavior.
- **Deliverables:** Metrics, structured logs, trace points, and failure-injection scenarios for leader loss, disk delays, and request retries.
- **Out of scope:** Full production SRE playbooks.
- **Validation/demo criteria:** Produce repeatable failure demonstrations with observable recovery and bounded unavailability.

## Milestone 7: sharding-ready routing abstraction

- **Objective:** Introduce routing and placement seams without changing client semantics.
- **Deliverables:** Internal `placement(tenant_id, resource_id) -> raft_group_id` abstraction, routing layer, and shard-compatible list semantics.
- **Out of scope:** Live online rebalancing across many groups.
- **Validation/demo criteria:** Show that the service can route requests through a placement API while still targeting a single group.

## Milestone 8: hardening and benchmark/demo suite

- **Objective:** Prepare a realistic demonstration of correctness and performance boundaries.
- **Deliverables:** Benchmark plan, repeatable demo scenarios, limit documentation, and hardening notes for operational readiness gaps.
- **Out of scope:** Claiming production readiness.
- **Validation/demo criteria:** Present a benchmark/demo suite that clearly communicates expected throughput, failover behavior, and remaining risks.
