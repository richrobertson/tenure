# Milestone 8 demo and benchmark report

Milestone 8 hardens the v1 startup path and adds a repeatable local evaluation story for the current single-group replicated lease service.

This runbook gives a reviewer two ways to evaluate Tenure:

1. a deterministic in-process evaluator for `demo` and `benchmark`
2. a manual daemon walkthrough with explicit static config and HTTP commands

The milestone intentionally stays local-first and dependency-light. It does not claim production readiness.

## What Milestone 8 adds

- clustered startup validation for static config, peer endpoints, and data-directory expectations
- rejection of reused data directories when the persisted node identity does not match the configured node ID
- a repeatable `demo` evaluator covering bootstrap, lifecycle, `NOT_LEADER`, retries, fencing turnover, recovery, and one failure-injection scenario
- a repeatable `benchmark` evaluator covering lifecycle latency, small-cluster throughput, failover availability, and restart catch-up timing
- a tiny helper script to write local three-node config files for manual daemon review

## Build and validation

Run the focused Milestone 8 validation slice:

```bash
sbt "testOnly com.richrobertson.tenure.runtime.StartupValidationSpec com.richrobertson.tenure.eval.LocalEvaluationSpec com.richrobertson.tenure.service.RaftIntegrationSpec com.richrobertson.tenure.observability.ObservabilityFailureInjectionSpec"
```

Run the full build if you want the broader regression pass:

```bash
sbt test
```

## Repeatable evaluator commands

### Demo report

```bash
sbt "runMain com.richrobertson.tenure.eval.LocalEvaluation demo --output /tmp/tenure-m8-demo-report.json"
cat /tmp/tenure-m8-demo-report.json
```

Expected report shape:

- `command = "demo"`
- `clusterSize = 3`
- `scenarios` includes:
  - `cluster_bootstrap`
  - `lease_lifecycle`
  - `not_leader_read`
  - `idempotent_retry`
  - `fencing_turnover`
  - `failure_injection_delay`
  - `clean_shutdown_restart`
- every scenario reports `success = true`

The demo evaluator uses explicit in-process `ClusterConfig` values, actual replicated services, persisted local directories, clean shutdown/restart, and the existing Milestone 6 failure injector. It is the narrowest repeatable end-to-end path in the repo.

### Benchmark report

```bash
sbt "runMain com.richrobertson.tenure.eval.LocalEvaluation benchmark --iterations 20 --parallelism 4 --output /tmp/tenure-m8-benchmark.json"
cat /tmp/tenure-m8-benchmark.json
```

Expected report shape:

- `command = "benchmark"`
- `latency` contains `acquire`, `renew`, and `release`
- each latency metric includes `samples`, `minMs`, `p50Ms`, `p95Ms`, `maxMs`, and `averageMs`
- `throughput` reports total operations, wall-clock milliseconds, and derived ops/sec
- `failover` reports the time from leader shutdown to a successful renew on the replacement leader
- `recovery` reports the time for a restarted node to recover a follower view of committed state

Interpretation guidance:

- these numbers are local characterization only
- they are useful for comparing runs on the same machine or after local code changes
- they are not production claims

## Manual daemon walkthrough

Generate explicit local config files:

```bash
scripts/milestone-8/write-local-cluster-config.sh /tmp/tenure-m8 9400 9500
```

Start the cluster in three terminals:

```bash
sbt "run -- /tmp/tenure-m8/config/node-1.json"
sbt "run -- /tmp/tenure-m8/config/node-2.json"
sbt "run -- /tmp/tenure-m8/config/node-3.json"
```

Use these headers in the requests below:

```bash
-H 'X-Tenure-Principal-Id: demo-user' \
-H 'X-Tenure-Principal-Tenant: demo-tenant'
```

### 1. Successful acquire / get / renew / release

Acquire against the leader:

```bash
curl -s http://127.0.0.1:9501/v1/leases/acquire \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-a","holder_id":"holder-a","ttl_seconds":15,"request_id":"demo-acquire-1"}'
```

Expected outcome:

- HTTP `200`
- response includes `lease_id`, `expiry_time`, `fencing_token`, and `created=true`

Read the same lease from the leader:

```bash
curl -s http://127.0.0.1:9501/v1/leases/demo-tenant/resource-a \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant'
```

Expected outcome:

- HTTP `200`
- `found=true`
- `lease.status="ACTIVE"`

Renew and then release using the returned `lease_id`:

```bash
curl -s http://127.0.0.1:9501/v1/leases/renew \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-a","lease_id":"<lease-id>","holder_id":"holder-a","ttl_seconds":20,"request_id":"demo-renew-1"}'

curl -s http://127.0.0.1:9501/v1/leases/release \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-a","lease_id":"<lease-id>","holder_id":"holder-a","request_id":"demo-release-1"}'
```

Expected outcome:

- both return HTTP `200`
- renew returns `renewed=true`
- release returns `released=true`

### 2. `NOT_LEADER` behavior

Read from a follower instead of the leader:

```bash
curl -s http://127.0.0.1:9502/v1/leases/demo-tenant/resource-a \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant'
```

Expected outcome:

- HTTP `409`
- body includes `"code":"NOT_LEADER"`
- `leader_hint` points to the current leader API endpoint once leadership is known

### 3. Idempotent retry behavior

Replay the same acquire against the leader with the same `request_id`:

```bash
curl -s http://127.0.0.1:9501/v1/leases/acquire \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-retry","holder_id":"holder-r","ttl_seconds":10,"request_id":"demo-retry-1"}'

curl -s http://127.0.0.1:9501/v1/leases/acquire \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-retry","holder_id":"holder-r","ttl_seconds":10,"request_id":"demo-retry-1"}'
```

Expected outcome:

- both responses match
- the second call is a replay, not a new lease admission

### 4. Fencing-token turnover

Acquire, release, and reacquire the same resource with a different holder:

```bash
curl -s http://127.0.0.1:9501/v1/leases/acquire \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-fence","holder_id":"holder-old","ttl_seconds":10,"request_id":"demo-fence-1"}'
```

Expected outcome:

- HTTP `200`
- response includes the first `lease_id` and `fencing_token`

Release that lease using the returned `lease_id`, then reacquire:

```bash
curl -s http://127.0.0.1:9501/v1/leases/release \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-fence","lease_id":"<first-lease-id>","holder_id":"holder-old","request_id":"demo-fence-release-1"}'

curl -s http://127.0.0.1:9501/v1/leases/acquire \
  -H 'Content-Type: application/json' \
  -H 'X-Tenure-Principal-Id: demo-user' \
  -H 'X-Tenure-Principal-Tenant: demo-tenant' \
  -d '{"tenant_id":"demo-tenant","resource_id":"resource-fence","holder_id":"holder-new","ttl_seconds":10,"request_id":"demo-fence-2"}'
```

Expected outcome:

- the release returns HTTP `200` with `released=true`
- the second acquire succeeds
- the second `fencing_token` is greater than the first one
- this is the local proof that stale writers can be rejected downstream

### 5. Clean shutdown / restart

Stop one node, then restart it with the same config:

```bash
sbt "run -- /tmp/tenure-m8/config/node-1.json"
```

Expected outcome:

- the restarted node recovers from its local data directory
- the cluster elects or retains a valid leader
- the restarted node catches up to committed lease state

Inspect current in-process signals on a loopback-bound node:

```bash
curl -s http://127.0.0.1:9501/debug/observability
```

Expected outcome:

- counters include leadership, request, and recovery signals
- structured events include restart and recovery records

## Known limits

- Milestone 8 does not claim production readiness.
- The benchmark suite is intentionally local and small; it is for characterization, not capacity planning.
- v1 still uses one shared Raft group, direct TCP peer RPC, static config, local disk, leader-only reads, and no DNS or transport multiplexing.
- The failure-injection scenario remains an in-process evaluator path rather than a public daemon API.
