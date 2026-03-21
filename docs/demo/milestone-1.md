# Milestone 1 local prototype demo

Milestone 1 is complete. The repository now includes a runnable single-node Scala 3 prototype for the core lease lifecycle.

## What Milestone 1 delivered

- runnable single-node HTTP service
- in-memory lease state machine
- deterministic expiration through an injected clock in tests
- request validation for the local API
- tenant-scoped resource identity using `(tenant_id, resource_id)`
- minimal local HTTP API for acquire, renew, release, and get

## Run locally

```bash
sbt run
```

The server listens on `0.0.0.0:8080`. The no-argument `sbt run` path starts the single-node prototype, while clustered mode still uses `sbt 'run -- <config-path>'`.

## Example calls

```bash
curl -X POST http://localhost:8080/v1/leases/acquire \
  -H 'content-type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: acme' \
  -d '{"tenant_id":"acme","resource_id":"scheduler-primary","holder_id":"worker-1","ttl_seconds":15,"request_id":"req-1"}'

curl http://localhost:8080/v1/leases/acme/scheduler-primary \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: acme'

# absent leases return 200 with {"found":false,...} rather than HTTP 404
```

Because this milestone is intentionally single-node, there is no leader routing, persistence, Raft replication, or fencing token issuance yet.

## Milestone boundary

Milestone 1 intentionally stops at a single-node, in-memory, non-replicated prototype. It does not add clustering, embedded Raft, durable persistence, snapshots, or a production deployment model.

Milestone 2 is next. It adds embedded Raft integration, replicated command flow, leader handling and redirection, and durable replicated log semantics without broadening the architecture.
