# Tenure v1 Operating Model

## Overview

Tenure v1 is intended to run as a foundational coordination daemon, not as an ordinary app-tier microservice. The default operating model is one self-contained process per Linux host or VM with local durable state, explicit peer configuration, and direct TCP connectivity between nodes.

## Why Tenure is treated as a P0 / foundational daemon

Tenure provides lease authority that higher-level services may rely on for singleton work, leader election, and stale-writer protection. That makes it a tier-0 control-plane primitive: it should be able to start early, remain understandable under failure, and avoid correctness dependencies on other distributed control-plane systems.

## Runtime dependencies

v1 correctness may depend on:

- the Linux / OS process model
- local disk
- TCP/IP networking
- local configuration files
- local credential or certificate material
- local process supervision such as `systemd`

## Explicit non-dependencies

v1 correctness and startup must not depend on:

- DNS
- Kubernetes
- service mesh
- external service discovery
- remote config service
- external SQL or NoSQL databases
- Redis or cache services
- another coordination service

These systems may exist around Tenure in some environments, but they are optional conveniences rather than part of the correctness boundary.

## Node identity and static bootstrap config

Each node should start with a static node ID and a local configuration file that declares the cluster membership needed for bootstrap. v1 peer configuration should use explicit node IDs and concrete transport endpoints such as `10.0.0.12:7000`.

Hostnames may exist as operator-friendly labels, but hostname resolution is not required for correctness. Peer identity for consensus is based on the configured node ID plus the configured transport endpoint.

## Peer communication model

Nodes communicate directly with each other over statically configured peer links. The cluster should be able to form, elect a leader, replicate commands, and recover after restart using only local configuration plus reachable peer endpoints.

Long-lived peer connections are preferred in v1 because they keep connection management simple and predictable during bootstrap and failover.

## TCP transport choice in v1

Inter-node Raft communication uses TCP in v1. This choice keeps the initial transport simple, widely deployable, and compatible with direct-host daemon operation. UDP is out of scope for v1.

## Why multiplexing is deferred in v1

v1 does not introduce transport multiplexing. The first implementation should use a straightforward non-multiplexed TCP transport so bootstrap behavior, fault handling, and recovery remain easy to reason about.

If a future release needs multiplexing for operational or performance reasons, it should be added behind a stable transport abstraction. That future change must not alter lease semantics, the state machine, or the client-facing service contract.

## Local storage expectations

Each node is expected to have a writable local data directory for Raft metadata, the replicated log, snapshots, and any materialized lease-state indexes. The system replicates commands and authoritative state transitions, not filesystem contents.

At a high level, the local persistence contract is:

- `metadata.json` tracks Raft term/vote/commit/apply (`lastApplied`) progress
- `log.jsonl` stores ordered replicated entries
- `snapshot.json` stores the latest local recovery snapshot of materialized service state
- `node-id` binds that directory to one configured node ID so operators do not accidentally reuse persisted state under a different identity

Restart recovery then rebuilds the node from snapshot-plus-log replay before the node rejoins the cluster. For a more detailed layer overview and flow diagrams, see [the architecture spec](../architecture/v1.md) and [request-flow diagrams](../diagrams/request-flow.md).

## Direct-host deployment assumptions

The default deployment model is direct installation on Linux compute hosts or VMs, supervised by `systemd` or an equivalent local process manager. Container packaging is optional, but Tenure v1 should not be described as if Kubernetes or another orchestrator is required for basic operation.

## Optional future conveniences that are not required in v1

Future versions may add conveniences such as DNS-based discovery, richer orchestration integration, or transport multiplexing if they are justified and kept behind stable abstractions. None of those conveniences are required for v1 bootstrap, peer communication, or correctness.
