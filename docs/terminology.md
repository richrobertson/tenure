# Terminology

| Term | Definition |
| --- | --- |
| Lease | Exclusive, time-bounded ownership of a resource. |
| TTL | The duration for which a lease remains valid unless renewed or released earlier. |
| Fencing token | A monotonically increasing token issued on successful lease acquisition and used by downstream systems to reject stale writers. |
| Tenant | The primary isolation boundary for identity, authorization, quotas, and observability. |
| Resource | A logical object addressed as `(tenant_id, resource_id)`. |
| Holder | The client identity recorded as the current owner of a lease. |
| Authoritative history | The ordered sequence of accepted state transitions produced through consensus. |
| Placement | The mapping from `(tenant_id, resource_id)` to a `raft_group_id`. |
| Raft group | A set of replicas that maintain a single replicated log and state machine. |
| Linearizability | A consistency property in which completed operations appear to take effect atomically in a single real-time-respecting order. |
