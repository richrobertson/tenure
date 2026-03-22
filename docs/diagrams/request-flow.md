# Request Flow Diagrams

These diagrams are intentionally narrow and v1-specific. They are meant to help reviewers understand the major local flows exercised by the daemon, demo suite, and benchmark harness without reverse-engineering the implementation.

## Clustered startup validation and bootstrap

```mermaid
flowchart TD
    A[Load static ClusterConfig] --> B[Validate nodeId, local peer, ports, hosts, dataDir]
    B -->|invalid| C[Fail fast with operator-readable error]
    B -->|valid| D[Prepare persistence layout and node-id marker]
    D -->|incompatible persisted state| E[Refuse startup]
    D -->|ok| F[Bind raft and API listeners]
    F --> G[Join fixed local peer set]
    G --> H[Elect leader]
    H --> I[Serve lease API]
```

## v1 Raft and persistence layer overview

```mermaid
flowchart TD
    A[HTTP / service layer] --> B[LeaseService and request validation]
    B --> C[RaftNode leader path]
    C --> D[Replicated log entries]
    D --> E[Committed state-machine apply]
    E --> F[Materialized ServiceState]
    C --> G[Persisted metadata.json]
    D --> H[Persisted log.jsonl]
    E --> I[Occasional persisted snapshot.json (threshold-based)]
    J[node-id marker and dataDir validation] --> G
    J --> H
    J --> I
```

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

## Release request flow

```mermaid
sequenceDiagram
    participant C as Client
    participant L as Raft Leader
    participant F as Followers
    participant SM as Lease State Machine

    C->>L: Release(tenant_id, resource_id, lease_id, holder_id, request_id)
    L->>L: Validate holder, request_id, active lease
    L->>F: Replicate release command
    F-->>L: Ack
    L->>SM: Apply release
    SM-->>L: released=true
    L-->>C: ReleaseResponse(...)
```

## Leader election overview

```mermaid
sequenceDiagram
    participant N1 as Node 1
    participant N2 as Node 2
    participant N3 as Node 3

    N1->>N1: Election timeout fires
    N1->>N1: Become candidate, increment term
    N1->>N2: RequestVote(term, lastLogIndex, lastLogTerm)
    N1->>N3: RequestVote(term, lastLogIndex, lastLogTerm)
    N2-->>N1: Vote granted
    N3-->>N1: Vote granted
    N1->>N1: Majority reached, become leader
    N1->>N2: AppendEntries heartbeat
    N1->>N3: AppendEntries heartbeat
```

## Command replication, commit, and apply

```mermaid
sequenceDiagram
    participant C as Client
    participant L as Leader
    participant F1 as Follower 1
    participant F2 as Follower 2
    participant P as Local persistence
    participant SM as ServiceState materializer

    C->>L: Mutating lease request
    L->>L: Build replicated command
    L->>P: Append local log entry (metadata persisted on commit/apply)
    L->>F1: AppendEntries(entry)
    L->>F2: AppendEntries(entry)
    F1->>F1: Persist entry locally
    F2->>F2: Persist entry locally
    F1-->>L: Ack(matchIndex)
    F2-->>L: Ack(matchIndex)
    L->>L: Advance commitIndex on majority
    L->>SM: Apply committed command
    SM-->>L: Updated materialized lease state
    L-->>C: Success response
```

## Follower read rejected with `NOT_LEADER`, then retried

```mermaid
sequenceDiagram
    participant C as Client
    participant F as Follower
    participant L as Leader

    C->>F: GetLease(tenant_id, resource_id)
    F-->>C: 409 NOT_LEADER + leaderHint
    C->>L: Retry GetLease using leaderHint
    L->>L: Read authoritative leader state
    L-->>C: 200 GetLeaseResponse(found/status)
```

## Snapshot and compaction path

```mermaid
sequenceDiagram
    participant L as Leader or follower
    participant R as Raft runtime
    participant SM as Materialized ServiceState
    participant P as Persistence

    L->>R: commitIndex advances
    R->>R: Check snapshot threshold
    R->>SM: Read current materialized state
    SM-->>R: ServiceState at committed index
    R->>P: saveSnapshot(lastIncludedIndex, lastIncludedTerm, serviceState)
    R->>P: saveMetadata(commitIndex, lastApplied, term)
    P-->>R: Snapshot durable
    R->>R: Retain snapshot reference for future recovery
```

## Clean shutdown and restart recovery

```mermaid
sequenceDiagram
    participant O as Operator / Evaluator
    participant N as Restarted Node
    participant L as Current Leader
    participant P as Persistence
    participant F as Followers

    O->>N: Stop node cleanly
    N->>N: Stop runtime loops and wait for in-flight operations
    O->>N: Start node with same config and dataDir
    N->>P: Validate node-id marker and reopen persisted state
    N->>L: Rejoin cluster
    L->>N: Send log catch-up / snapshot as needed
    L->>N: Resume steady-state heartbeats / replication
    N-->>O: Follower view matches committed lease state
```

## Recovery bootstrap from snapshot plus committed log

```mermaid
flowchart TD
    A[Open data directory] --> B[Validate node-id marker and file layout]
    B --> C[Load metadata.json]
    C --> D[Load snapshot.json if present]
    D --> E[Load log.jsonl entries]
    E --> F[Choose base state from snapshot or empty ServiceState]
    F --> G[Recover commitIndex = max(snapshot.lastIncludedIndex, persisted metadata.commitIndex)]
    G --> H[Replay committed log entries with index in (snapshot.lastIncludedIndex, commitIndex]]
    H --> I[Set lastApplied = recovered commitIndex]
    I --> J[Node can rejoin as follower and catch up]
```

## Failure-injection delay demo path

```mermaid
sequenceDiagram
    participant E as Evaluator
    participant L as Leader
    participant I as Failure Injector
    participant F as Followers
    participant C as Client

    E->>I: Enable delay injection for leader replication path
    C->>L: Acquire / Renew request
    L->>I: Inject configured delay
    I-->>L: Continue after delay window
    L->>F: Replicate command
    F-->>L: Ack
    L-->>C: Successful response with elevated latency
    E->>I: Disable injected delay
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
