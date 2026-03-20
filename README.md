# Tenure

Tenure is a distributed lease coordination service for multitenant systems. It provides strongly consistent, time-bounded ownership of shared resources identified as `(tenant_id, resource_id)`. In v1 it is intended to operate as a foundational P0 / tier-0 coordination daemon, not as an ordinary app-tier microservice.

Unlike a simple distributed lock service, Tenure treats lease expiry, fencing, and replicated authoritative history as first-class concerns. The goal is to provide a durable blueprint for building a service that remains correct under retries, failover, and partial failure.

## Overview

Tenure is modeled as a replicated state machine. Lease state transitions are serialized through consensus, and v1 intentionally runs all state through a **single shared Raft group with logical multitenancy**. That choice reduces implementation complexity while preserving strong correctness semantics and a clean path to future sharding.

The design introduces a placement abstraction now:

```text
placement(tenant_id, resource_id) -> raft_group_id
```

In v1, placement always resolves to one group. Client semantics are defined so that future sharding does not require an API redesign.

The default operating model is a self-contained Linux daemon running directly on compute hosts or VMs with local durable state. v1 correctness depends on the local process, local disk, TCP/IP networking, local configuration, and local credentials. It does not depend on DNS, Kubernetes, or an external database.

Inter-node Raft communication uses statically configured TCP peer endpoints in v1. Peer bootstrap and steady-state correctness use explicit node IDs plus concrete transport endpoints, and transport multiplexing is intentionally deferred until a stable transport abstraction proves necessary.

## Requirements

- Exclusive leases only in v1.
- TTL-based acquisition, renewal, release, and inspection.
- First-class multitenancy using `(tenant_id, resource_id)`.
- Strongly consistent state transitions serialized through Raft.
- Fencing tokens for stale-writer protection.
- Idempotent client request IDs.
- Local durable persistence on each node.
- Explicit tenant isolation, quotas, and observability.

## Guarantees

- Linearizable lease operations for the active Raft group, with `GetLease` and `ListLeases` served by the leader in v1.
- At most one valid lease holder per `(tenant_id, resource_id)` at a time.
- Leader-mediated expiration and admission decisions.
- Monotonic fencing tokens per resource.
- Durable recovery from local persisted Raft and state-machine state.
- No cross-tenant access by API contract.

## Non-Goals

- Production implementation in this bootstrap pass.
- Shared leases or multi-resource atomic acquisition.
- Cross-tenant operations.
- Immediate multi-group deployment.
- Orchestration, CI/CD, benchmarking, or networking stack scaffolding beyond the documented v1 operating model.

## Status

This repository is in the documentation-first bootstrap phase. The current contents define the v1 architecture, API contract, roadmap, and key architectural decisions.

## Project documents

- [Docs index](docs/index.md)
- [Architecture spec](docs/architecture/v1.md)
- [Runtime operating model](docs/runtime/v1-operating-model.md)
- [API contract](docs/api/lease-service.md)
- [Milestones](docs/milestones.md)
- [Scala-first v1 implementation plan](docs/implementation/v1-plan.md)
- [ADR: v1 single Raft group and logical multitenancy](docs/adr/0001-v1-single-raft-group-logical-multitenancy.md)
- [Request-flow diagrams](docs/diagrams/request-flow.md)
- [Terminology](docs/terminology.md)

## Why leases are different from simple distributed locks

A lock service can stop at mutual exclusion. A lease service must also define what happens when time passes, nodes fail, clients retry, or an old holder continues acting after ownership has logically expired. Tenure therefore treats TTLs, authoritative expiration decisions, idempotency, and fencing tokens as part of the core contract rather than optional details.

## Why this project is interesting

Tenure is intentionally scoped at the point where distributed-systems correctness becomes interesting without requiring a large codebase. It captures replicated state-machine design, lease semantics, multitenant isolation, persistence boundaries, and a realistic future-sharding story in a compact repository.

## Future work

Future milestones add a local prototype, embedded Raft integration, persistent recovery, observability, failure injection, and a sharding-ready routing layer while preserving the core client contract established in the docs.
