# Milestone 7 demo: routing and local multi-group simulation

This runbook documents the narrow Milestone 7 surface: explicit routing, deterministic placement, and local multi-group simulation while keeping the lease API unchanged.

## What Milestone 7 adds

- `API -> Router -> GroupRuntime` as the explicit request path
- deterministic `placement(tenant_id, resource_id) -> group_id` via a stable hash router
- `GroupId` and `GroupRuntime` as the logical Raft-group boundary
- local multi-group simulation with multiple logical groups in one process
- group-scoped observability labels/fields such as `group_id`

## Default behavior

Single-group mode is still the default.

```bash
sbt run
```

That keeps the v1 runtime model unchanged while still exercising the routing layer internally.

## Local multi-group simulation

Run three logical groups in one local process:

```bash
sbt "run -- --local-groups 3"
```

This still exposes the same HTTP API:

- `POST /v1/leases/acquire`
- `POST /v1/leases/renew`
- `POST /v1/leases/release`
- `GET /v1/leases/{tenant_id}/{resource_id}`
- `GET /v1/tenants/{tenant_id}/leases`

The difference is internal: requests are routed to a logical group before per-group execution.
Routed mutating requests still preserve tenant-scoped request-id deduplication, and acquires still enforce active-lease quota checks, before group-local mutation.

## What to observe

- the same `(tenant_id, resource_id)` always maps to the same `group_id`
- different resources spread across configured groups
- `ListLeases` remains tenant-scoped even when leases live in different logical groups
- structured events and metric labels include `group_id`

If the server is bound on loopback, inspect the bounded in-process snapshot:

```bash
curl -s http://127.0.0.1:8080/debug/observability
```

Look for:

- `routing_decisions_total`
- `routing_broadcasts_total`
- per-group `service_requests_total{group_id=...}`
- `routing.decision` and `routing.broadcast` events with `group_id` or group-count fields

## Deterministic validation commands

Run the routing and multi-group validation specs:

```bash
sbt "testOnly com.richrobertson.tenure.routing.HashRouterSpec com.richrobertson.tenure.service.RoutedLeaseServiceSpec com.richrobertson.tenure.service.MultiGroupRecoverySpec"
```

## Still out of scope

Milestone 7 does not add:

- live rebalancing
- shard migration
- dynamic partition management
- cross-node multi-group control-plane logic
- production-grade placement policy beyond deterministic local hashing
