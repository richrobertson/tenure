# Tenure

**Tenure** is a distributed lease coordination service that provides **exclusive, time-bounded ownership of shared resources** for multitenant systems.

The service is designed for components that require a strongly consistent coordination primitive under failure, retry, and partition conditions. Typical use cases include leader election, singleton job execution, shard ownership, and coordination of access to externally mutable resources.

Tenure is implemented as a replicated state machine. Lease state transitions are serialized through consensus, producing a single authoritative history for each resource. In v1, the system uses a **single Raft group with logical multitenancy**, with all resources scoped by `(tenant_id, resource_id)`.

---

## Overview

Tenure provides a coordination primitive that is:

- **Strongly consistent**: lease operations are serialized through a replicated log  
- **Time-bounded**: leases expire unless explicitly renewed  
- **Failure-aware**: ownership is automatically revoked when clients fail  
- **Multitenant**: all resources are isolated by tenant  
- **Extensible**: designed to support future sharding without changing client semantics  

---

## Requirements

### Functional

- Provide APIs for:
  - `Acquire`
  - `Renew`
  - `Release`
  - `GetLease`
- Represent resources as `(tenant_id, resource_id)`
- Support **exclusive leases only** (v1)
- Grant leases with bounded TTLs
- Require explicit renewal prior to expiration
- Return a **fencing token** on successful acquisition
- Support idempotent client requests via request IDs
- Allow inspection of current lease state

### Multitenancy

- Enforce tenant isolation at API and state-machine layers
- Prevent cross-tenant access or mutation
- Support per-tenant quotas:
  - max active leases
  - max TTL
  - request rate limits
- Emit tenant-scoped observability signals

### Consistency and correctness

- Serialize all lease state transitions through consensus
- Maintain a single authoritative ownership history per resource
- Ensure at most one valid lease holder per `(tenant_id, resource_id)`
- Provide fencing tokens for downstream stale-writer protection
- Avoid correctness dependencies on:
  - synchronized filesystem contents
  - follower-local expiration decisions

### Evolution

- Maintain a stable client-facing API independent of placement
- Define internal placement abstraction:

```text
placement(tenant_id, resource_id) -> raft_group_id
```

- Resolve all requests to a single group in v1
- Preserve clear extension points for future sharding

---

## Guarantees

### Lease safety

For a given `(tenant_id, resource_id)`, lease operations are applied in a single globally ordered history. At most one lease is valid at any time.

### Linearizability

Successful lease operations are linearizable with respect to the replicated state machine.

### Time-bounded ownership

Leases are granted with a TTL. Clients must renew before expiration or assume ownership is lost.

### Fencing tokens

Each successful lease grant returns a monotonically increasing fencing token (scoped to the resource). Downstream systems can use this to reject stale actors.

### Failure tolerance

Node failures and leader changes do not violate lease safety. Temporary unavailability may occur, but correctness is preserved.

### Persistence model

Each node maintains local durable state (Raft log, metadata, snapshots). The system replicates **commands**, not filesystem contents.

### Expiration semantics

Expiration is leader-mediated. Followers do not independently mutate authoritative lease state based on local clocks.

---

## Non-Goals (v1)

- Shared or reader-writer leases  
- Multi-resource atomic acquisition  
- Cross-tenant coordination  
- Cross-shard transactions  
- Multi-group Raft deployment (v1 is single group)  
- Geo-distributed quorum placement  
- Global fairness guarantees  
- Hierarchical resource ownership  
- General-purpose lock service abstraction  
- Direct replication of files or directories  

---

## Status

This repository is currently in the **architecture and planning phase**.  
See [`docs/architecture/v1.md`](docs/architecture/v1.md) for the full design and [`docs/milestones.md`](docs/milestones.md) for the implementation roadmap.
