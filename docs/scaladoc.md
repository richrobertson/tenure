# Scaladoc Maintenance

## Why this exists

Tenure uses Scaladoc as both:

- a fast reference for experienced maintainers
- an onboarding layer for readers who are new to the codebase

That means documentation should not stop at terse API labels, but it also should not bury the summary under a wall of prose. The intended style is:

1. Start with a short summary sentence.
2. Follow with a slightly deeper explanation of purpose, invariants, or usage.
3. Add examples only when they materially help a newcomer understand how a type or method is meant to be used.

Experienced readers should be able to stop after the first sentence. Newer readers should be able to keep going and understand why the code is shaped the way it is.

## What to document

When changing Scala code, keep Scaladoc current for:

- package-level files and package entry points
- public classes, traits, objects, enums, and type aliases
- public methods and important constructors/factories
- important state-model types and error/result types
- internal helpers when their behavior is subtle, correctness-critical, or non-obvious

In this repository, correctness-sensitive code should be documented especially carefully:

- lease lifecycle semantics
- idempotency behavior
- expiration/time semantics
- persistence and recovery rules
- Raft leader/follower behavior
- routing and multitenancy invariants

## Writing style

Prefer this pattern:

```scala
/**
 * Short summary for quick reference.
 *
 * Deeper explanation of what this type or method does, when it is used, and any important invariants.
 *
 * Example:
 * {{{
 * // small example only when it helps
 * }}}
 */
```

Guidelines:

- Prefer precise language over marketing language.
- Explain invariants and consequences, not just mechanical implementation.
- Use domain terms consistently: tenant, resource, lease, fencing token, placement, authoritative history.
- Keep examples short and realistic.
- Avoid repeating obvious parameter names unless the relationship between them matters.
- Update adjacent docs when Scaladoc reveals an architectural mismatch.

## Maintainer checklist

Before merging Scala changes that update Scaladoc comments or any public Scala API or signature:

- add or update Scaladoc for every new package/type/member you introduced
- refresh Scaladoc on existing types whose behavior changed
- make sure the first sentence works as a terse reference
- make sure the deeper paragraph explains the behavior clearly enough for a new reader
- include examples only where they reduce confusion
- run `sbt doc` to make sure the generated API site still builds cleanly
- run `sbt compile` as well when the change affects Scala behavior or signatures

## Generate the Scaladoc site

Generate the local API docs with:

```bash
sbt doc
```

The generated HTML entry point is written under:

```text
target/scala-*/api/index.html
```

In practice, after a Scala version bump you should expect the exact directory name to change. If you need to inspect the raw generator output directly, locate the newest matching path under `target/scala-*/api/`.

Review the generated site locally by opening:

```text
target/scala-*/api/index.html
```

## Keep the docs section current

When the codebase grows or package structure changes:

- keep `README.md` and `docs/index.md` pointing at the current documentation entry points
- keep this runbook aligned with the actual `sbt doc` output path
- add links to important generated API docs in reviewer notes or release notes when useful

## Reviewer checklist

When reviewing a PR that changes Scaladoc comments or public Scala APIs or signatures:

- check that the changed types and methods have updated Scaladoc where needed
- check that `sbt doc` still succeeds for the changed code
- spot-check the generated site by opening `target/scala-*/api/index.html` locally after generation
- call out stale or missing source Scaladoc before merge

Scaladoc should evolve with the codebase, not as a one-time cleanup.
