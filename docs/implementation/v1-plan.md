# Scala-first v1 Implementation Plan

## Overview

This document maps the v1 architecture into a Scala-first backend plan without changing the architecture itself. The goal is to keep the implementation explicit, testable, and understandable to engineers who are not Scala specialists.

The backend should be organized around an explicit replicated state machine. Domain rules for leases, expiration, fencing, and idempotency should remain visible in code rather than being hidden behind framework magic. Infrastructure concerns such as transport, Raft integration, persistence, and authentication should sit behind narrow interfaces. The preferred implementation shape is a pure core with effectful edges so correctness logic stays easy to read, test, and review.

## Recommended implementation style

- Use **Scala 3**.
- Use **Cats Effect** as the preferred runtime model for effectful service code.
- Keep the service architecture explicit and boring.
- Follow a **pure core / effectful edges** structure.
- Prefer algebraic data types, immutable state transitions, and explicit interfaces.
- Keep the lease state machine framework-agnostic and pure so the core logic can be tested without a network stack, storage engine, or Raft runtime.
- Favor code that remains legible to senior backend engineers coming from Go, Java, or C#, not only Scala specialists.

## Intended pure/effectful split

### Pure core

The pure core should contain logic that depends only on inputs, authoritative state, and explicit policy values. Typical contents:

- domain identifiers and value types
- lease records
- commands and responses
- validation rules that depend only on inputs and state
- state-machine transitions
- fencing-token issuance semantics
- dedupe semantics
- expiry and admission logic given authoritative time

### Effectful edges

The effectful edges should orchestrate side effects and infrastructure concerns around that core. Typical contents:

- API transport
- Raft submit and wait-for-commit behavior
- leader routing and discovery
- persistence and snapshot IO
- clock access
- authorization and quota lookups
- metrics and logging

Cats Effect should be used to isolate side effects at service boundaries, keep transport and persistence concerns out of domain models, centralize time behind a clock abstraction, and support deterministic tests around pure state-machine transitions.

## Suggested module boundaries

A plausible Scala package or module layout for v1 is:

- `model`: domain identifiers, lease records, commands, events, error types, and policy inputs.
- `statemachine`: pure state transition logic for acquire, renew, release, expiry evaluation, dedupe decisions, and fencing-token issuance.
- `service`: request orchestration, validation, idempotency coordination, and leader-only read enforcement.
- `api`: transport-facing request and response models plus serialization adapters.
- `routing`: leader resolution, placement lookup, and request forwarding decisions.
- `raft`: adapter boundary between Tenure commands and the embedded Raft library.
- `persistence`: durable state abstractions for snapshots, log-adjacent metadata, and dedupe retention.
- `auth`: tenant-scoped identity and authorization checks.
- `quota`: tenant policy evaluation for request rate, active lease count, and TTL limits.
- `time`: clock abstraction and time-calculation helpers.
- `testkit`: fake clocks, in-memory stores, deterministic command drivers, and failure-test helpers.

Dependency direction should stay inward:

- `model` depends on nothing.
- `statemachine` depends only on `model`.
- `service` depends on `statemachine` plus explicit interfaces for routing, Raft submission, persistence, auth, quota, and time.
- `api`, `routing`, `raft`, `persistence`, `auth`, `quota`, and `time` depend inward on `service` contracts rather than the reverse.
- `testkit` may depend on any interface layer needed to provide deterministic fakes, but production correctness logic stays in `model` and `statemachine`.

Correctness logic lives in `model` and `statemachine`. `service` orchestrates effects and dependency boundaries. `api`, `raft`, `persistence`, `routing`, `auth`, `quota`, and `time` are adapters or infrastructure-facing layers. Future sharding should remain a routing concern and must not leak into the core state machine.

## Core abstractions to keep explicit

The first implementation should preserve clear seams for the following pieces:

- **API layer:** parses requests, validates shape, maps errors, and does not contain lease-state business logic.
- **Leader resolution / routing:** decides whether the local node can serve the request, whether to redirect, and how to preserve leader-only reads in v1.
- **Raft adapter boundary:** converts service-level commands into replicated log entries and exposes committed results back to the service layer.
- **Lease state machine:** owns authoritative state transitions, fencing-token issuance, expiry decisions, and duplicate-request handling.
- **Persistence abstraction:** hides storage details for snapshots, retained dedupe state, and any materialized lease indexes.
- **Snapshot codec:** keeps snapshot serialization explicit and versionable rather than scattering encoding logic through the domain.
- **Clock/time source abstraction:** provides leader time for TTL evaluation and deterministic tests for renewal and expiry boundaries.
- **Authz / tenant policy:** enforces tenant-scoped access and prevents mismatches between auth context and request payload.
- **Quota enforcement:** evaluates per-tenant request rate, active lease count, and max TTL policy.
- **Idempotency store or dedupe index:** ensures repeated mutating requests resolve to the original result and survive leader changes.

Pure/domain-oriented pieces should include the domain models, commands, events, and state transitions. Effectful/infrastructure-oriented pieces should include transport, persistence, Raft integration, auth providers, and metrics/logging.

## Scala-specific guidance

- Model commands, events, and state transitions with sealed traits, enums, and case classes.
- Centralize validation and state transitions so lease rules are not duplicated across handlers.
- Isolate side effects at service boundaries.
- Avoid leaking transport or storage concerns into domain models.
- Prefer deterministic tests around pure state-machine transitions and policy checks.
- Keep serialization concerns separate from domain types where possible.
- Represent error categories explicitly so retry guidance and API mapping stay mechanical.
- Use a small explicit clock interface instead of calling wall-clock APIs throughout the codebase.

This guidance is intentionally practical. It does not require a heavyweight actor architecture or a specific functional library.

## Recommended implementation order

A practical coding sequence aligned with the roadmap is:

1. Domain model and state-machine transition tests.
2. Single-node in-memory service with explicit clock and request validation.
3. Persistence abstractions for snapshots, retained dedupe state, and materialized lease views.
4. Raft adapter integration for replicated mutating commands.
5. API surface, error mapping, and leader routing behavior.
6. Quotas, idempotency enforcement, and fencing-token checks.
7. Snapshots, replay, and recovery procedures.
8. Observability hooks and failure-injection testing.

This order keeps correctness rules visible early and delays infrastructure complexity until the domain behavior is already well tested.

## Early testing strategy

Early tests should focus on deterministic correctness rather than throughput:

- Pure state-machine tests for acquire, renew, release, expiry, and duplicate handling.
- Idempotency tests that distinguish transport retries from new semantic attempts.
- Fencing-token monotonicity tests across release and reacquire sequences.
- Expiry and renewal boundary tests using a fake clock.
- Recovery tests from snapshot plus log replay.
- Leader-only read behavior tests for `GetLease` and `ListLeases`.
- Authorization and quota tests scoped by tenant.

## Things that must remain abstract

These areas should remain abstract in v1 so future sharding does not require an API redesign:

- placement and routing
- Raft group identity
- storage backend details
- network transport specifics

## Things that must remain explicit

These areas must remain explicit in code because they are central to correctness:

- authoritative lease state
- leader-mediated expiration
- request-id dedupe semantics
- fencing-token issuance semantics
- tenant-scoped identity and quotas
