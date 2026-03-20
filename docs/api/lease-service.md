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
- If the same `request_id` is retried for the same logical operation, the response must be idempotent.
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
- Renewal does not create a new fencing token epoch; it returns the current token.

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
- Cross-tenant listing is not supported.
- The API should remain shard-compatible by avoiding any contract that implies global scans across all tenants.

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
}
```

Notes:
- `ALREADY_HELD` indicates another active holder exists.
- `LEASE_EXPIRED` indicates the target lease is no longer valid for renewal or release semantics.
- `LEASE_MISMATCH` indicates holder or lease identity does not match authoritative state.
- `NOT_LEADER` may include redirection metadata in a real implementation.

## Idempotency expectations

- Mutating requests (`Acquire`, `Renew`, `Release`) require a client-supplied `request_id`.
- Idempotency scope is per tenant and logical operation target.
- Reusing a `request_id` for a different operation or different target should be rejected.
- Servers should retain deduplication history long enough to cover realistic retry windows.

## Examples

### Acquire

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
