# Repository Instructions for AI Agents

These instructions apply whenever an AI agent is creating or reviewing a pull request in this repository.

## Keep README.md in sync with code changes

This repository publishes a library (`hmpps-spring-boot-sqs`) whose `README.md` is the primary source of
documentation for consumers. Whenever you create or review a PR, check the diff against the current `README.md`
(and the supporting docs it links to: `readme-docs/RunningLocally.md`, `readme-docs/CONTRIBUTING.md`,
`readme-docs/PUBLISHING.md`, and `release-notes/*.md`) and consider whether the README needs updating:

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
