# Request Flow Diagrams

## Acquire request flow

```mermaid
sequenceDiagram
    participant C as Client
    participant R as Router/Frontend
    participant L as Raft Leader
    participant F as Followers
    participant SM as Lease State Machine

    C->>R: Acquire(tenant_id, resource_id, holder_id, ttl, request_id)
    R->>R: placement(tenant_id, resource_id) -> group-1
    R->>L: Forward request
    L->>L: Validate tenant, quota, idempotency, TTL
    L->>F: Replicate log entry
    F-->>L: Ack
    L->>SM: Apply acquire
    SM-->>L: lease_id, expiry_time, fencing_token
    L-->>C: AcquireResponse(...)
```

## Renew request flow

```mermaid
sequenceDiagram
    participant C as Client
    participant L as Raft Leader
    participant SM as Lease State Machine

    C->>L: Renew(tenant_id, resource_id, lease_id, request_id)
    L->>L: Validate holder, request_id, active lease window
    L->>L: Replicate renew command through Raft
    L->>SM: Apply renew
    SM-->>L: updated expiry_time
    L-->>C: RenewResponse(...)
```

## Leader failover and recovery path

```text
client request
    |
    v
old leader fails
    |
    v
remaining quorum elects new leader
    |
    v
new leader replays durable Raft log / snapshot
    |
    v
state machine becomes authoritative again
    |
    v
clients retry with same request_id for idempotent handling
```

## Future routing / placement abstraction

```text
request(tenant_id, resource_id)
           |
           v
placement(tenant_id, resource_id) -> raft_group_id
           |
     +-----+-----+
     |           |
    v1       future
  group-1   group-N
```
