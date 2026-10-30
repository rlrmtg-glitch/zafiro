# Governed Assistant Security Hardening

## Scope and status

This branch is an isolated hardening effort based on the source snapshot
`e81342effdf662fbdebb0aa3a86856f3037f5bac`. It must not be merged into
`main` or released until the security test suite and human review pass.

The design follows: OBSERVE → ANALYSE → PROPOSE → HUMAN REVIEW →
EXPLICIT APPROVAL → EXECUTE → VERIFY → AUDIT.

A model, tool, skill, MCP server, background agent, or privileged process
cannot grant itself authority. Approval is tied to one exact operation and
must never be inherited by another session or background agent.

## Phase 0 — Preservation

- Preserve the audited commit as the base of this branch.
- Keep the original debug APK hash in the audit record:
  `33e72b0b85c5d026a1ca0992e3394fc107dbd07247c00fdadb79ab23eea20c13`.
- No changes to `main`, no phone installation, and no release signing.

## Phase 1 — Governance core

Required implementation:
- deny-by-default capability policy;
- typed risk classification and operation proposals;
- approval requests bound to session, actor, capability, canonical arguments,
  and an expiry;
- one-use approval tokens with replay protection;
- cancellation and device-lock invalidation;
- append-only audit events with operation digest and outcome.

An approval UI is not sufficient by itself: the execution boundary must
revalidate the token and operation digest immediately before execution.

## Phase 2 — Execution containment

Until every execution path is brokered, executable capabilities remain
unavailable to the model. This includes shell, Python, custom Python,
SSH, MCP, root, Shizuku, screen interaction, persistent memory mutation,
and skill activation. Read-only functionality may only be re-enabled after
its actual implementation is verified read-only.

The initial exposure gate intentionally returns no tools by default. It is
not yet a complete broker and must not be represented as one.

## Phase 3 — Android permissions

- Remove broad permissions that are not essential to an explicitly approved
  feature.
- Disable cleartext traffic.
- Disable backup by default and add narrowly scoped data-extraction rules.
- Audit exported activities, services, providers, Binder identity checks,
  accessibility and Xposed entry points.
- Keep privileged operations behind a small, auditable broker.

Manifest changes require compatibility review; do not remove permissions
blindly where a documented user-facing feature depends on them.

## Phase 4 — Data and credentials

- Encrypt sensitive settings and database fields with Android Keystore-backed
  keys where supported.
- Redact credentials from logs, tool traces, MCP arguments and exception text.
- Define retention, export, deletion and backup behaviour.
- Persistent memory mutations require a human-approved proposal.

## Phase 5 — Network and dependencies

- Enforce TLS and endpoint allowlists.
- Require MCP authentication and route MCP tool calls through the same broker.
- Pin SSH host keys; never accept arbitrary host keys.
- Update affected dependencies only with compatibility tests and a verified
  dependency graph.
- Keep secrets out of source, logs, build outputs and artifacts.

## Phase 6 — Background agents

- Background agents may observe and prepare proposals, not execute consequential
  operations.
- Bound agent count, runtime, tool calls, network calls and resource use.
- Cancellation, session closure, expiry and device lock revoke pending authority.
- No approval-token inheritance across agents or sessions.

## Phase 7 — Supply chain and build

- Pin GitHub Actions to immutable commit SHAs.
- Validate the Gradle wrapper distribution checksum.
- Enable dependency locking and verification.
- Restrict repositories and produce an SBOM and build provenance.
- Keep debug and release workflows separate; protect release signing material.

## Phase 8 — Security verification

The release gate is zero successful unapproved consequential operations.
Tests must cover shell, Python, argument substitution after approval, token
expiry/replay, agent self-approval, background execution, MCP bypass, skill
policy override, memory mutation, screen interaction, root/Shizuku, SSH host
verification, credential leakage, cleartext traffic, cancellation, device
lock, process restart and concurrent requests.

## Current implementation status

- Phase 0: branch created from the audited commit.
- Phase 1: initial capability exposure policy added; full approval manager,
  operation-bound token lifecycle and tamper-evident audit remain incomplete.
- Phase 2: tool resolution now fails closed by exposing no builtin tools,
  custom Python tools or MCP servers under the default policy. This is a
  containment gate, not proof that every internal/direct execution path is
  brokered.
- Phases 3–8: documented requirements only; implementation and verification
  remain outstanding.
