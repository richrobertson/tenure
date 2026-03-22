# Contributing

## Expectations

- Keep branches and pull requests focused, minimal, and reviewable.
- For architecture or correctness changes, update the relevant docs first or in the same PR.
- Preserve tenant isolation and future sharding hooks in every design change.
- Update `docs/milestones.md`, the architecture spec, and ADRs when the architecture meaningfully changes.
- Avoid speculative scaffolding or large implementation drops without an agreed milestone.

## Pull requests

A good PR for this repository should:

- make one coherent change
- explain why the change is needed
- call out any assumptions
- include doc updates when behavior, scope, or design changes

## Scaladoc maintenance

- Keep Scaladoc updated when Scala behavior changes, not as a later cleanup task.
- Document new packages, types, and important methods in the same style as the existing code: a terse first sentence for quick reference, then a deeper explanation for newcomers.
- Add small examples when they make a confusing type or method easier to understand.
- Refresh Scaladoc when invariants change, especially around lease semantics, Raft behavior, persistence, recovery, routing, and multitenancy.
- When you change Scaladoc comments or any public Scala API or signature, run `sbt doc` before merging so the generated API site still builds cleanly. Run `sbt compile` as well when the change affects Scala behavior or signatures.
- When Scala files change, update the relevant source Scaladoc in the same PR rather than leaving it for later cleanup.
- Reviewers should treat stale or missing source Scaladoc as a documentation bug, not as optional follow-up work.
- Use [docs/scaladoc.md](docs/scaladoc.md) as the maintainer runbook for style and generation guidance.
