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

## Incremental implementation update

- Added `OperationApprovalVault`: cryptographically random 256-bit bearer tokens, SHA-256 token storage, exact-operation digest binding, session and policy-version binding, 60-second default expiry, one-use consumption, bounded outstanding-token count, and session/global revocation.
- Added unit tests for exact-operation binding, replay, modified arguments, session mismatch, policy mismatch, expiry and revocation.
- Changed `android:allowBackup` to `false` as a conservative data-protection baseline.
- Hardened the MCP server adapter with an explicit host-side tool allowlist that is empty by default. The MCP service configuration alone can no longer expose tools; a tool must also be explicitly allowlisted in code. Removed raw argument logging from the MCP invocation start event to reduce credential and personal-data leakage. This is a compatibility-impacting change: app data will no longer participate in Android backup/restore by default. A future explicit export/restore design is still required.
- Hardened MCP exception handling so exception messages, stack traces, image file paths and parser exception details are not copied into logs or returned to the remote caller. MCP callers receive a generic failure message; detailed diagnostics must be captured only in a separately protected local diagnostic channel.
- Added constructor bounds to the approval vault (1 ms–5 minutes, at most 256 outstanding tokens), overflow-safe expiry calculation and tests for invalid bounds.
- Important limitation: the approval vault is a primitive, not a complete authorization system. No trusted approval UI/controller is connected, and it does not itself prove that `approveAfterHumanConfirmation` was called as a result of a genuine human action. Do not wire this method to model-, MCP-, skill- or background-agent-controlled code.

## Current implementation status

- Phase 0: branch created from the audited commit.
- Phase 1: deny-by-default exposure policy and an operation-bound, expiring,
  one-use approval-token vault are present with focused unit tests. A trusted
  human approval UI/controller, execution-time broker integration, durable
  tamper-evident audit log and device-lock/process-restart revocation are not
  implemented.
- Phase 2: model-facing tool resolution and the MCP server adapter fail closed
  by default. Direct/internal execution paths have not all been proven brokered.
- Phase 3: Android backup is disabled by default. Cleartext traffic remains
  enabled and broad storage/overlay/accessibility/foreground-service
  permissions remain; exported-component and privileged-entry-point reviews
  are incomplete.
- Phase 4: MCP raw-argument and exception-detail logging/output are reduced.
  Keystore-backed encryption, database/settings protection, credential
  redaction across all logs, retention and secure export/deletion remain
  outstanding.
- Phase 5: no verified TLS, SSH host-key or dependency upgrades have been
  applied.
- Phase 6: background-agent execution limits and authority revocation are
  outstanding.
- Phase 7: dependency verification/locking, immutable workflow action pins,
  wrapper checksum validation, SBOM and provenance are outstanding.
- Phase 8: tests have been added but have not been run; no build or dynamic
  security test has been completed.
