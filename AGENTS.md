# AGENTS.md

## Repo goals

Tenure is a documentation-first repository for a distributed lease coordination service. The repo should remain useful both as an implementation blueprint and as a portfolio-quality systems design artifact.

## Engineering principles

- Keep changes small, coherent, and reviewable.
- Prefer doc-first evolution for architecture, correctness, and API changes.
- Preserve correctness over convenience, especially around lease safety, expiration, fencing, and failover behavior.
- Prefer durable abstractions over speculative scaffolding.
- Keep the repository easy for a future engineer to extend into a real implementation.

## Terminology

- **Lease**: Exclusive, time-bounded ownership for a resource.
- **Tenant**: Isolation boundary for resources, quotas, authorization, and observability.
- **Resource**: Object identified by `(tenant_id, resource_id)`.
- **Fencing token**: Monotonic token used by downstream systems to reject stale actors.
- **Placement**: Mapping from `(tenant_id, resource_id)` to a Raft group.
- **Authoritative history**: The globally ordered state transitions accepted through consensus.

## Non-goals for v1

- Multi-resource transactions.
- Shared or reader-writer leases.
- Cross-tenant operations.
- Immediate multi-group sharding.
- Large implementation scaffolding without an approved architecture update.

## Style expectations for future changes

- Update docs before or alongside behavior changes.
- Prefer precise markdown over vague planning notes.
- Use concrete examples when describing distributed-systems behavior.
- Keep diagrams lightweight and source-controlled.
- Do not add generated code, broad tooling, or CI/CD unless justified by the milestone plan.

## Multitenancy rules

- Treat multitenancy as first-class in every design change.
- Represent resources as `(tenant_id, resource_id)`.
- Do not introduce cross-tenant scans or APIs that would weaken tenant isolation.
- Keep quotas, authorization, and observability tenant-aware.

## Sharding extension rules

- Preserve the placement abstraction: `placement(tenant_id, resource_id) -> raft_group_id`.
- v1 always resolves placement to one shared group, but new designs must not assume that forever.
- Avoid correctness arguments that require global scans across all tenants or all resources.

## Reviewability expectations

- Prefer the minimum clean file set needed for the current change.
- Separate architecture decisions, API contracts, and roadmap updates into obvious files.
- When architecture changes, update the relevant ADRs, milestones, and specs in the same change.

## Doc-first rule

Any meaningful change to lease semantics, expiration behavior, Raft integration, multitenancy, persistence, or sharding must begin with or include a documentation update.

## Runtime and transport guardrails

- Preserve bootstrap-safe startup and correctness without requiring DNS or external control-plane dependencies.
- Treat direct-host Linux daemon deployment as the default v1 operating model, even if container packaging is added later.
- Keep peer transport behind an explicit abstraction so future multiplexing can be added without changing the state machine or lease semantics.
