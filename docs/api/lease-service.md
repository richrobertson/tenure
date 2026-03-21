# Lease Service API Contract

This document defines an implementation-neutral API contract for the Tenure lease service.

## Common types

```text
ResourceRef {
  tenant_id: string
  resource_id: string
}

LeaseRef {
  tenant_id: string
  resource_id: string
  lease_id: string
}

LeaseView {
  tenant_id: string
  resource_id: string
  lease_id: string
  holder_id: string
  status: enum { ACTIVE, EXPIRED, RELEASED, ABSENT }
  expiry_time: timestamp
  fencing_token: uint64
  version: uint64
}
```

## General v1 rules

- All resources are addressed as `(tenant_id, resource_id)`.
- `Acquire`, `Renew`, and `Release` require a client-supplied `request_id`.
- Every request is evaluated in a tenant-aware principal context; v1 uses explicit request metadata rather than a full external identity provider.
- `GetLease` is a leader-only linearizable read in v1.
- `ListLeases` is also a leader-only linearizable read in v1.
- Clients must route `GetLease` and `ListLeases` to the current leader in v1, or rely on server-side redirection if supported.
- Follower-served reads are out of scope for v1.
- Lease validity is determined by authoritative replicated state plus leader-mediated expiry rules, not by client-local wall clock.
- TTL is validated against global service policy and per-tenant policy.

## Authorization context

Implementations must associate every request with a principal identity and a principal tenant. The current prototype uses `X-Tenure-Principal-Id` and `X-Tenure-Principal-Tenant` headers. The principal tenant must match the request `tenant_id` or the request fails with `FORBIDDEN`.

## Acquire

```text
AcquireRequest {
  tenant_id: string
  resource_id: string
  holder_id: string
  ttl_seconds: uint32
  request_id: string
}

AcquireResponse {
  lease_id: string
  expiry_time: timestamp
  fencing_token: uint64
  created: bool
}
```

Semantics:
- Creates a new exclusive lease if the resource is currently unleased or the previous lease has authoritatively expired.
- `expiry_time` is computed from leader time when the acquire is admitted or applied according to one centralized implementation rule.
- The requested TTL must satisfy the service's global and per-tenant maximum TTL policy.
- The service may reject TTLs that are too short to operate safely.
- If the same `request_id` is retried for the same logical operation, the response must be idempotent.
- Reusing the same `request_id` with different holder, TTL, resource, or operation semantics must be rejected.
- `fencing_token` increases on each successful new grant for the resource.

## Renew

```text
RenewRequest {
  tenant_id: string
  resource_id: string
  lease_id: string
  holder_id: string
  ttl_seconds: uint32
  request_id: string
}

RenewResponse {
  lease_id: string
  expiry_time: timestamp
  fencing_token: uint64
  renewed: bool
}
```

Semantics:
- Extends the active lease if the caller still owns it and renewal is admitted by the leader.
- `expiry_time` is recomputed from leader time, not extended from a client-local clock.
- Renewal does not create a new fencing token epoch; it returns the current token.
- The requested TTL must satisfy the same TTL policy checks as `Acquire`.
- Clients are expected to renew before expiry with a safety margin.
- The service may reject pathological renewal cadences that create undue control-plane load.

## Release

```text
ReleaseRequest {
  tenant_id: string
  resource_id: string
  lease_id: string
  holder_id: string
  request_id: string
}

ReleaseResponse {
  lease_id: string
  released: bool
  expiry_time: timestamp
  fencing_token: uint64
}
```

Semantics:
- Marks the active lease as released if the holder is authorized to do so.
- Repeated release requests with the same `request_id` should resolve idempotently.
- A release does not advance the fencing token by itself; the next successful acquire receives the next token.

## GetLease

```text
GetLeaseRequest {
  tenant_id: string
  resource_id: string
}

GetLeaseResponse {
  found: bool
  lease: LeaseView
}
```

Semantics:
- Returns the current materialized lease view for the resource in the addressed tenant.
- In v1 this is a leader-only linearizable read. Clients must route the request to the current leader, or rely on server-side redirection if the deployment supports it.
- A replica that is not the current leader must return `NOT_LEADER` rather than serving a potentially stale read.
- Followers do not transparently serve `GetLease` in v1.
- Implementations may expose logically expired state as `EXPIRED` until compaction or replacement, but admission control is leader authoritative.

## ListLeases

```text
ListLeasesRequest {
  tenant_id: string
  page_size: uint32
  page_token: string
  filter_status: enum { ANY, ACTIVE, EXPIRED, RELEASED }
}

ListLeasesResponse {
  leases: repeated LeaseView
  next_page_token: string
}
```

Semantics:
- Lists leases within one tenant only.
- In v1 this is a leader-only linearizable read. Clients must route the request to the current leader, or rely on server-side redirection if the deployment supports it.
- A replica that is not the current leader must return `NOT_LEADER` rather than serving a potentially stale list response.
- Cross-tenant listing is not supported.
- The API should remain shard-compatible by avoiding any contract that implies global scans across all tenants.

## Time and expiration contract

- `expiry_time` is derived from leader time at admission or apply, not from client-supplied timestamps.
- Followers do not independently expire leases or authorize new holders.
- Clients should treat `expiry_time` as the authoritative service deadline for that lease epoch.
- Clients should renew with a safety margin; waiting until the exact displayed expiry increases the chance of losing the lease.
- Example TTL values in docs are illustrative only; they are not normative defaults.
- Implementations should centralize time calculations behind an explicit clock abstraction so expiry and renewal behavior can be tested deterministically.

## Error model

```text
Error {
  code: enum {
    INVALID_ARGUMENT
    UNAUTHORIZED
    FORBIDDEN
    NOT_FOUND
    ALREADY_HELD
    LEASE_EXPIRED
    LEASE_MISMATCH
    QUOTA_EXCEEDED
    RATE_LIMITED
    NOT_LEADER
    UNAVAILABLE
    INTERNAL
  }
  message: string
  retryable: bool
  leader_hint: optional string
}
```

Notes:
- `ALREADY_HELD` indicates another active holder exists.
- `LEASE_EXPIRED` indicates the target lease is no longer valid for renewal or release semantics.
- `LEASE_MISMATCH` indicates holder or lease identity does not match authoritative state.
- `NOT_LEADER` applies to leader-only reads as well as mutating operations, and may include optional redirect metadata such as a leader hint or endpoint.
- `retryable` is advisory; client behavior must still distinguish transport retry from semantic retry.

## Error handling and retry guidance

| Error code | Meaning | Safe retry behavior | Expected client action |
| --- | --- | --- | --- |
| `INVALID_ARGUMENT` | Request shape, identifiers, or TTL policy is invalid. | Do not retry unchanged. | Fix the request before sending again. |
| `UNAUTHORIZED` | Caller identity is missing or invalid. | Do not retry unchanged. | Refresh or supply valid credentials. |
| `FORBIDDEN` | Caller is authenticated but not allowed for the tenant or resource. | Do not retry unchanged. | Stop and escalate or request the needed permission. |
| `NOT_FOUND` | The referenced resource or lease record does not exist in the requested context. | Retry only if the caller expects concurrent creation or delayed visibility after a prior successful write. | Re-check tenant, resource, and lease identifiers. |
| `ALREADY_HELD` | Another active holder currently owns the lease. | Semantic retry is allowed later with a new `request_id`; transport retry of the same request is only for uncertain delivery. | Back off, observe expiry or release, then try a fresh acquire. |
| `LEASE_EXPIRED` | The target lease is no longer valid for renewal or release. | Do not retry the same renewal or release as a new semantic attempt. | Treat the lease as lost and, if appropriate, start a fresh acquire flow. |
| `LEASE_MISMATCH` | `lease_id`, `holder_id`, or target resource does not match authoritative state. | Do not retry unchanged. | Refresh lease state and reconcile caller state before proceeding. |
| `QUOTA_EXCEEDED` | Tenant policy rejected the request because a quota limit was exceeded. | Retry only after conditions change. | Reduce usage, wait for leases to clear, or adjust tenant policy. |
| `RATE_LIMITED` | The service rejected the request due to rate limiting. | Yes, with backoff and jitter. | Retry later, preserving the same `request_id` for the same mutating attempt. |
| `NOT_LEADER` | The contacted replica is not the current leader for the addressed Raft group. | Yes. | Redirect to the hinted leader if provided, otherwise re-resolve leader and retry. |
| `UNAVAILABLE` | The service cannot currently process the request due to failover or transient unavailability. | Yes, with backoff and deadline handling. | Retry against the service, re-resolving leadership as needed. |
| `INTERNAL` | An unexpected server-side failure occurred. | Cautious transport retry is acceptable for mutating calls if the original outcome is unknown. | Retry with the same `request_id` for the same mutating attempt, then escalate if repeated. |

## Idempotency expectations

- Mutating requests (`Acquire`, `Renew`, `Release`) require a client-supplied `request_id`.
- Idempotency scope is per tenant and logical operation target.
- Reusing a `request_id` for a different operation or different target must be rejected.
- Duplicate delivery of the same mutating request must return the original logical outcome rather than applying the command twice.
- Servers should retain deduplication history long enough to cover realistic retry windows and leader changes.

### Transport retry vs. semantic retry

- A **transport retry** replays the same logical mutating attempt with the same `request_id` because the client is unsure whether the original request succeeded.
- A **semantic retry** is a new attempt after a definitive business outcome such as `ALREADY_HELD`, `LEASE_EXPIRED`, or a user decision to try again later.
- Transport retries for `Acquire`, `Renew`, and `Release` must reuse the same `request_id`.
- Semantic retries must use a new `request_id`.

### Duplicate `request_id` behavior

- If a duplicate `request_id` matches the original tenant, operation, and target, the service must return the original logical result.
- If the same `request_id` is reused for a different operation or different `(tenant_id, resource_id)`, the service must reject it.
- The dedupe decision is part of the authoritative replicated state and must survive leader changes and normal recovery windows.

## Examples

### Authorization context

Implementations must associate every request with a principal identity and a principal tenant. The current prototype uses `X-Tenure-Principal-Id` and `X-Tenure-Principal-Tenant` headers. The principal tenant must match the request `tenant_id` or the request fails with `FORBIDDEN`.

## Acquire

```json
{
  "request": {
    "tenant_id": "acme",
    "resource_id": "scheduler/primary",
    "holder_id": "worker-17",
    "ttl_seconds": 15,
    "request_id": "8c9a0f0a-1"
  },
  "response": {
    "lease_id": "lease-0001",
    "expiry_time": "2026-03-20T12:00:15Z",
    "fencing_token": 44,
    "created": true
  }
}
```

### Renew

```json
{
  "request": {
    "tenant_id": "acme",
    "resource_id": "scheduler/primary",
    "lease_id": "lease-0001",
    "holder_id": "worker-17",
    "ttl_seconds": 15,
    "request_id": "8c9a0f0a-2"
  },
  "response": {
    "lease_id": "lease-0001",
    "expiry_time": "2026-03-20T12:00:25Z",
    "fencing_token": 44,
    "renewed": true
  }
}
```

### Release

```json
{
  "request": {
    "tenant_id": "acme",
    "resource_id": "scheduler/primary",
    "lease_id": "lease-0001",
    "holder_id": "worker-17",
    "request_id": "8c9a0f0a-3"
  },
  "response": {
    "lease_id": "lease-0001",
    "released": true,
    "expiry_time": "2026-03-20T12:00:11Z",
    "fencing_token": 44
  }
}
```

### Get

```json
{
  "request": {
    "tenant_id": "acme",
    "resource_id": "scheduler/primary"
  },
  "response": {
    "found": true,
    "lease": {
      "tenant_id": "acme",
      "resource_id": "scheduler/primary",
      "lease_id": "lease-0001",
      "holder_id": "worker-17",
      "status": "ACTIVE",
      "expiry_time": "2026-03-20T12:00:25Z",
      "fencing_token": 44,
      "version": 7
    }
  }
}
```

### List

```json
{
  "request": {
    "tenant_id": "acme",
    "page_size": 2,
    "page_token": "",
    "filter_status": "ACTIVE"
  },
  "response": {
    "leases": [
      {
        "tenant_id": "acme",
        "resource_id": "scheduler/primary",
        "lease_id": "lease-0001",
        "holder_id": "worker-17",
        "status": "ACTIVE",
        "expiry_time": "2026-03-20T12:00:25Z",
        "fencing_token": 44,
        "version": 7
      }
    ],
    "next_page_token": ""
  }
}
```
