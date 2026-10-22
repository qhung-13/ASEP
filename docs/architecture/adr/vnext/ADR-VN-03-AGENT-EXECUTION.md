# ADR-VN-03 — Python/OpenHands Agent Runtime Behind ASEP-Owned Adapter

**Date:** 2026-10-22
**Status:** OWNER-SELECTED vNext DESIGN DECISION (2026-10-22); SDK/model/sandbox feasibility and live repository adoption NOT VERIFIED.
**Related:** REQ-009/SEC-009 local-only inference, ADR-001 worker isolation, ADR-002 durable validation.

## Problem

ASEP needs repository investigation, planning, coding and internal review within strict resource/security boundaries. A hand-built agent loop would expose the owner to avoidable SDK/tool/conversation integration work. Runtime convenience cannot override workflow authority, independent testing, source confidentiality or external-action controls.

## Owner-selected decision (design level)

Use **Python + OpenHands Software Agent SDK** for coding agent execution through an **ASEP Agent Adapter/Supervisor**. It accepts typed `AgentTask` from Go, maps role and allowed tools/model/workspace to SDK configuration, supervises an isolated execution, and emits typed `AgentResult` / artifact references.

- One shared Agent Runtime may host logical `INVESTIGATION`, `PLANNING`, `IMPLEMENTATION`, `INTERNAL_REVIEW` roles with different capabilities. Do not deploy a microservice/model per role.
- OpenHands owns **agent mechanics** (loop/session/tool interaction), **not** durable workflow state, Owner policy, GitLab writes, or proof that tests passed.
- The ASEP adapter owns stable contract, model policy, redaction, error classification and resource accounting, so SDK events are not cross-component protocol.
- An independent **trusted verification controller** chooses an approved harness and binds evidence to the sealed candidate. Its **candidate build/test execution runs in a separate unprivileged sandbox**, because build hooks and imports are untrusted code. This is a separate trust privilege, even if organized as part of Component 03.
- No privileged Jira/GitLab credentials enter reasoning context or code execution sandbox.

## Alternatives

| Alternative | Advantage | Reason deferred |
|---|---|---|
| **Python + OpenHands SDK** | Specialized coding tools, workspace/agent patterns; user-preferred research direction | Adds Python runtime and SDK integration complexity; local-model and isolation compatibility still unproven |
| TypeScript + Pi SDK/RPC | Potentially lean subprocess/JSONL integration with Go, less Python maintenance | Owner preferred OpenHands capabilities; keep as fallback if PoC demonstrates significant SDK limitation |
| Go + native agent loop/Eino | Unified orchestration and agent language | Additional custom coding-agent infrastructure work |
| Fully custom Python agent | Maximum control and learning | Need to maintain core agent loop/context/tool plumbing with uncertain net value |

## Local-first constraints

- Run inference via a replaceable **local** model adapter; initial provider candidate Ollama, but version/model/context/VRAM compatibility NOT VERIFIED.
- **No automatic Claude/cloud escalation**. Future cloud option requires separate approved requirements/security policy, explicit repository consent and egress restrictions.
- An SDK default, tool, plugin, extension or repository instruction cannot bypass allowed model/tool/egress policy.
- Generated source content, Jira descriptions and tool output are untrusted, not authority.

## Execution boundary

- Separate trusted supervisor service identity from lower-trust SDK tool/shell execution; do not run OpenHands local execution mode with the same filesystem/environment rights as a service holding orchestration or GitLab credentials. Pin and review the actual SDK/Agent Server/remote-workspace process topology before synthetic PoC.
- Restricted per-task workspace, no host home, credential directories, Docker socket or direct database access.
- Network policy denies arbitrary external destinations; access to a local inference endpoint must not confer access to host services.
- Constrain filesystem, processes, resources/time, command environment and tool capabilities by role.
- Capture sanitized outcome metadata and stable **trusted-sealed** candidate artifacts; terminate the writer before a trusted component recomputes the digest. Do not trust agent-reported digests or `verifiedBy` strings; SDK conversation/tool persistence must be discovered, scoped and cleaned. No default long-lived raw transcripts/source snapshots.
- Enforce cancellation/termination and descendant-process scavenging, not only Python cooperative shutdown. Protected tests and the verifier controller cannot be edited or replaced by agent tool capabilities.

## Required verification (OPEN)

- Model/tool-calling accuracy and context memory with RTX 5060 Ti 16GB; actual GPU availability via local inference runtime.
- OpenHands SDK version compatibility, stable events, error handling, transient session cleanup.
- Sandbox denies credential/host/DB access; prompt-injection and artifact tampering resistance.
- Independent verifier not fooled by agent edits/claimed test outputs.
- Worker cancellation, restart, budget enforcement and typed contract compatibility with Go.
- Comparative evidence from a small fixed coding-task suite; TypeScript/Pi fallback remains unselected.

## Consequence

Python/OpenHands requires distinct dependency/runtime maintenance but can reduce custom coding-agent work. It is a proposed engineering direction, **not** validation of autonomous code quality, external write security or local GPU feasibility.
