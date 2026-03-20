# Milestone 1 local prototype demo

The repository now includes a runnable single-node Scala 3 prototype for the Milestone 1 lease lifecycle.

## What it demonstrates

- in-memory lease state machine
- deterministic expiration through an injected clock in tests
- tenant-scoped resource identity using `(tenant_id, resource_id)`
- minimal local HTTP API for acquire, renew, release, and get

## Run locally

```bash
sbt run
```

The server listens on `0.0.0.0:8080`.

## Example calls

```bash
curl -X POST http://localhost:8080/v1/leases/acquire \
  -H 'content-type: application/json' \
  -d '{"tenantId":"acme","resourceId":"scheduler-primary","holderId":"worker-1","ttlSeconds":15}'

curl http://localhost:8080/v1/leases/acme/scheduler-primary
```

Because this milestone is intentionally single-node, there is no leader routing, persistence, Raft replication, or fencing token issuance yet.
