# ADR-VN-01 — Three Logical Components with Separate Runtime Boundaries

**Date:** 2026-10-22
**Status:** OWNER-ACCEPTED vNext DESIGN DIRECTION (2026-10-22); repository application and runtime proof NOT VERIFIED.
**Affected historical decisions:** ADR-001, later TSE-01. Their historical records remain intact; scope-specific supersession applies only to vNext after repository adoption.

## Context

ADR-001 chose a modular monolith with **one unspecified backend codebase** and separate API/Worker process roles for M01; Java/Spring Boot/Spring Modulith was selected **later** under TSE-01. The new bounded Jira-to-Draft-MR slice introduces independently recoverable orchestration and controlled agent execution. One codebase for all backend processes could reduce integration overhead, but would require implementing/maintaining the coding-agent runtime and Go concurrency objectives inside the Java backend.

## Owner-selected vNext decision (design level)

Use **three logical components**, not a generic microservices architecture:

1. **Application & Integrations:** Next.js/TypeScript UI+BFF and Java Spring Boot APIs; Keycloak integration, user/business authorization, policy configuration, Jira/GitLab read, and Trusted External Action Executor.
2. **Orchestration Core:** Go process with PostgreSQL-backed authoritative workflow state, gates, budgets, task scheduling and recovery.
3. **Agent Execution:** Python Agent Supervisor/ASEP adapter around OpenHands SDK and strongly isolated coding/verification execution.

No separate service per agent role. The initial deployment remains single-host, with explicit process/credential/isolation boundaries. The internal contracts remain ASEP-owned and framework-independent.

## Alternatives considered

| Alternative | Benefit | Cost / reason not preferred |
|---|---|---|
| Retain Java modular monolith and worker for all stages | Maximum current-code reuse, fewer runtimes | Agent integration and Go concurrency/learning goals less direct; could still work but not Owner-preferred research direction |
| All-Go backend and agent loop | Unified backend language, simple binaries | Replacing Spring application investments and coding-agent tooling adds migration/rebuild risk |
| **Java Application + Go Orchestrator + Python/OpenHands** | Capability specialization and planned learning outcomes; preserve existing GitLab API adapter | Multi-language CI, contracts, service auth, process ops and testing |
| Independent microservices for each module/role | Independent scaling | Distributed complexity unsupported by single-host initial requirements |

## Preserved invariants and precise supersession

- **Preserved from ADR-001:** separation of API from long-running execution, clear module boundaries, durable execution state, safety and testability; no AI-generated external write by implication.
- **Superseded for vNext scope after documentation adoption:** ADR-001's *one backend codebase* is no longer a physical deployment constraint; Spring Boot continues as the Application backend, not the Go workflow owner or Python Agent Runner.
- **Preserved from ADR-002:** atomic claims, fencing, failure recovery and independent GitLab publication control.
- Historical M01 code, tests and status are not deemed migrated/complete merely by accepting this ADR.

## Consequences

Additional processes, libraries, API versions, credentials and observability are required. The benefit is clear ownership, isolated tool execution and replaceable agent SDK. A component boundary is not automatically a network-security boundary; runtime identities/OS/container restrictions must be implemented and verified.

## Required proof before execution-grade acceptance

- Cross-language contract and service-auth tests; versioned schema compatibility.
- Process restart/failure isolation; no direct Agent PostgreSQL/business or privileged GitLab access.
- Reuse mapping for existing M01 assets, especially GitLab Read Adapter, based on real Git status.
- Security tests for sandbox, network and credentials.

## Open

Exact service authentication, routing topology, repo/build layout, implementation migration and cost/performance metrics remain NOT VERIFIED. The design direction is Owner-accepted; implementation evidence, exact service contracts and live repository adoption are still outstanding.
