# ADR 0001: v1 uses a single shared Raft group with logical multitenancy

- Status: Accepted
- Date: 2026-03-20

## Context

Tenure is a distributed lease coordination service for resources identified as `(tenant_id, resource_id)`. The service needs strong consistency, careful expiration semantics, stale-writer protection via fencing tokens, and a design that can grow into a sharded topology later.

At bootstrap time, the primary risk is architectural overreach: too many moving parts before lease correctness, persistence boundaries, and API semantics are stable. At the same time, the design should not trap future work in a single-group worldview.

## Decision

For v1, Tenure will:

- serialize all lease state transitions through one shared Raft group
- treat multitenancy as a logical concern within that group
- expose an internal placement abstraction now:

```text
placement(tenant_id, resource_id) -> raft_group_id
```

- define v1 placement so that every request resolves to the same group
- avoid APIs and correctness arguments that depend on cross-tenant global scans

## Consequences

### Positive

- Significantly reduces implementation complexity in the first milestone set.
- Makes linearizable lease behavior easier to reason about and document.
- Avoids premature partitioning of tenants or resources before access patterns are understood.
- Preserves a clean future routing layer without changing the external lease API.
- Keeps correctness work focused on Raft integration, lease semantics, idempotency, and fencing.

### Negative

- Throughput and storage scale are bounded by one group in v1.
- Hot tenants or hot resources can affect the shared control plane.
- Operational isolation between tenants is limited compared with physical sharding.
- Future sharding will still require data movement and routing work.

### Neutral / design constraints

- Tenant isolation must be enforced in the API, authorization, quotas, and observability layers even though data resides in one group.
- List operations must remain tenant-scoped and shard-compatible.
- Persistence must be described as per-node durable state rather than replicated filesystems.

## Alternatives considered

### 1. Shard by tenant immediately

This would improve isolation and make future scale-out more direct for large tenants. It was rejected for v1 because it adds routing, rebalancing, hotspot planning, and more operational complexity before the core lease state machine is proven.

### 2. Shard by `(tenant, resource)` immediately

This offers the most granular distribution and best theoretical load spread. It was rejected for v1 because it front-loads the hardest parts of partitioning, placement management, and observability while providing little value before real workload characteristics are known.

### 3. Use a non-Raft backing design such as DB-backed ad hoc locking

A database-backed design can look simpler operationally, but it weakens the architectural focus of this project. More importantly, ad hoc locking on top of a generic database often obscures expiration authority, fencing semantics, and failure behavior. It was rejected because Tenure is explicitly intended to be a replicated lease state machine with clearly documented consensus and persistence boundaries.
