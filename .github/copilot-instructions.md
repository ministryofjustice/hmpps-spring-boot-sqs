# Repository Instructions for AI Agents

These instructions apply whenever an AI agent is creating or reviewing a pull request in this repository.

## Keep README.md in sync with code changes

This repository publishes a library (`hmpps-spring-boot-sqs`) whose `README.md` is the primary source of
documentation for consumers. Whenever you create or review a PR, check the diff against the current `README.md`
(and the supporting docs it links to: `readme-docs/*.md` and `release-notes/*.md`) and consider whether the README
needs updating:

* **Where existing code has changed** (e.g. a property's name, default, validation rules, or behaviour; an endpoint's
  path, request/response shape, or semantics), check whether the README's description of that feature is now
  inaccurate or incomplete, and whether any new configuration properties it introduces are missing from the relevant
  property table (e.g. `HmppsSqsProperties`'s `QueueConfig`/`TopicConfig` tables).
* **Where a new feature has been added** (e.g. a new public function, extension function, bean, configuration
  property, or admin endpoint), check whether it forms part of the library's public API. If so, it should be
  documented — and if it's a feature consumers would reach for early (e.g. a new way to publish/send/listen to
  messages, or a new queue admin capability), it should be made prominent near the top of the README (in or near the
  [`Quick Start`](../README.md#quick-start) and [`Publishing & Sending Messages`](../README.md#publishing--sending-messages)
  sections), not buried only in a low-level reference table further down.
* Internal/private changes that don't affect how a consuming service would use the library (e.g. refactors, internal
  helper changes, test-only code) do not need a README update.

If you find the README is out of date, update it as part of the PR. If you're reviewing a PR and the README looks
like it needs an update but hasn't been changed, flag this in your review comments rather than silently approving.

## Always update Release Notes

Release notes (`release-notes/<major>.x.md`, e.g. `release-notes/7.x.md`, linked from the
[`Release Notes`](../README.md#release-notes) section of the README) should be updated for every PR that changes
published behaviour — this includes new features, behavioural changes, deprecations, and bug fixes, not just breaking
changes. Add an entry to the file for the current major version (create a new `release-notes/<next-major>.x.md` file,
linked from the README, if the PR bumps the major version in `build.gradle.kts`).

If you're creating a PR with a change that should be covered by release notes but the PR doesn't update them, add an
entry yourself. If you're reviewing a PR and it doesn't update release notes despite changing published behaviour,
flag this in your review comments rather than silently approving.

Also check that the version in `build.gradle.kts` has been bumped appropriately for the change — it's easy for an
author (human or agent) to forget this. If you're reviewing a PR and believe the change is breaking but the version
number has not been bumped as a major upgrade, flag this in your review comments. Do not fix it yourself — the
correct version bump is a judgement call for the author/maintainers to make.

## Syncing test-app / test-app-reactive with hmpps-template-kotlin

`test-app` and `test-app-reactive` were originally copied from
[`hmpps-template-kotlin`](https://github.com/ministryofjustice/hmpps-template-kotlin), the template all HMPPS Kotlin
projects are bootstrapped from. The intention is to keep them reasonably close to the template so that the tests in
this repo continue to exercise the library against a realistic, up-to-date consumer project.

**Only perform this sync when an engineer explicitly asks for it** — do not do this proactively as part of an
unrelated PR or review, since it's a deliberate, potentially large piece of work rather than routine maintenance.

When asked to perform the sync:

1. **First check whether there are any changes to apply.** Get a clean checkout of `hmpps-template-kotlin`'s `main`
   branch and diff it against the current `test-app` (and/or `test-app-reactive`), covering: `build.gradle.kts` and
   dependency versions, security/auth configuration, test infrastructure (mock servers, JWT helpers, base test
   classes), `application.yml`/`application-*.yml` properties, and deployment-only files (`.github/workflows`,
   `helm_deploy`, `Dockerfile`, `.java-version`, `renovate.json`, `applicationinsights*.json`). If there are no
   meaningful differences, say so and stop — do not make changes for the sake of it.
2. **Match the template as closely as possible — everything should be synced by default**, including dependency
   versions, new shared starter libraries, package renames, new template-introduced tests, and deployment-only/inert
   files. Don't treat any of these as optional judgement calls to raise with the engineer; the default is always to
   adopt the template's approach unless doing so would be actively wrong for `test-app` (e.g. a file that serves a
   genuine, different local-dev purpose in `test-app` rather than being an inert copy — flag cases like this rather
   than silently deviating). `test-app-reactive` does **not** need to stay in sync with `test-app` — that's up to the
   engineers maintaining the library, not something to reconcile as part of this task. Only ask clarifying questions
   for genuinely new judgement calls not covered by this guidance (or by prior answers already recorded in this
   section, once any are added).
3. Ask the engineer which branch to work on — e.g. a new branch name to create, or confirmation that the current
   branch is fine (as long as it isn't `main`). Do not assume or invent a branch naming convention.
4. Apply the agreed changes, **preserving the existing tests and their intent** — the goal is parity with the
   template's structure/config, not a rewrite of what the tests check. Deliberate, intentional deviations from the
   template (e.g. fixes needed because `test-app` uses this library in ways the template doesn't) should be preserved
   and called out, not silently reverted.
5. Verify the full test suite (and `ktlintCheck`) passes before presenting the change back for review, and
   explicitly flag any judgement calls made along the way so they can be reviewed before the PR is raised.
