# Milestone 4 demo: quotas, idempotency, and fencing tokens

Milestone 4 makes the multitenant contract explicit at the service boundary.

## What this demo proves

- transport retries reuse a tenant-scoped `request_id` and replay the original result
- reusing a `request_id` with different semantics is rejected deterministically
- per-tenant quotas reject excess active leases and TTLs above policy
- committed mutating commands carry the quota policy snapshot that admitted them so replay stays deterministic after quota changes
- cross-tenant access is rejected through an explicit principal-to-tenant authorization check
- fencing tokens increase on turnover and let downstream systems reject stale writers

## Principal headers

Every HTTP request carries:

- `X-Tenure-Principal-Id`
- `X-Tenure-Principal-Tenant`

The principal tenant must match the request `tenant_id`.

## Suggested validation commands

### Idempotent acquire replay

```bash
curl -sS \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: tenant-a' \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"tenant-a","resource_id":"resource-1","holder_id":"holder-1","ttl_seconds":10,"request_id":"req-1"}' \
  http://127.0.0.1:9001/v1/leases/acquire

curl -sS \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: tenant-a' \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"tenant-a","resource_id":"resource-1","holder_id":"holder-1","ttl_seconds":10,"request_id":"req-1"}' \
  http://127.0.0.1:9001/v1/leases/acquire
```

Expected result: both responses return the same `lease_id`, `expiry_time`, and `fencing_token`.

### Duplicate `request_id` with mismatched target

```bash
curl -sS \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: tenant-a' \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"tenant-a","resource_id":"resource-2","holder_id":"holder-1","ttl_seconds":10,"request_id":"req-1"}' \
  http://127.0.0.1:9001/v1/leases/acquire
```

Expected result: `INVALID_ARGUMENT` with an explanation that the `request_id` cannot be reused for different operation/resource/parameters.

### Cross-tenant rejection

```bash
curl -sS \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: tenant-b' \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"tenant-a","resource_id":"resource-1","holder_id":"holder-1","ttl_seconds":10,"request_id":"req-9"}' \
  http://127.0.0.1:9001/v1/leases/acquire
```

Expected result: `FORBIDDEN`.

### Fencing turnover

1. Acquire `tenant-a/resource-1` as `holder-1` and note `fencing_token=1`.
2. Let the lease expire or release it.
3. Acquire the same resource as `holder-2` and note `fencing_token=2`.
4. In downstream code, reject writes with any token lower than the highest token already accepted.

The automated stale-writer simulation lives in `FencingTokenSpec`.
