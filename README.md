# ASEP — Agentic Software Engineering Platform

ASEP is a personal, self-hosted engineering project exploring reliable AI-assisted Software Development Lifecycle (SDLC) workflows.

The project aims to build an enterprise-inspired AI engineering platform while developing practical skills in backend architecture, full-stack engineering, AI engineering, testing, security, and DevOps.

The first implementation milestone, **M01 — AI Merge Request Reviewer**, focuses on GitLab Merge Request code review using local AI inference and controlled publication.

## 1. Project Vision

The long-term target workflow is:

`Jira Ticket → Investigation → Planning → Human Approval → Implementation → Testing → GitLab Draft MR → AI Code Review → Human Merge`

ASEP is designed around several core principles:

- Human oversight for consequential operations.
- Evidence-based and actionable AI findings.
- Secure, local-only inference over source code.
- Durable, recoverable workflow execution.
- Explicit module boundaries and testable architecture.
- No automatic merging into protected branches.

ASEP is a personal engineering and learning project, not a production SaaS platform.

## 2. Current Implementation

The repository contains the initial application foundation introduced during **M01-02 — Application Bootstrap & Local Development Environment**.

| Component | Technology | Current capability |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Spring Modulith 2.1.1 | Application bootstrap, Actuator health, foundational tests |
| Frontend | Next.js 16.4.2, React, TypeScript | App Router, minimal ASEP landing page |
| Backend build | Maven Wrapper | Reproducible Maven execution and JAR packaging |
| Frontend tooling | Corepack + pnpm | Dependency installation, linting, type checking, production build |

Both applications have passed initial local build and runtime verification.

**Implementation boundary:** The current foundation is not a functioning AI Merge Request Reviewer. GitLab integration, authentication, AI inference, background processing, and publication workflows have not been implemented.

Application bootstrap verification does not establish completion of milestone M01.

## 3. Repository Structure

```text
ASEP/
├── backend/           # Spring Boot + Spring Modulith
├── frontend/          # Next.js App Router + TypeScript
├── docs/
│   ├── architecture/  # System architecture and ADRs
│   ├── development/   # Local development documentation
│   ├── discovery/     # Technical discovery
│   ├── governance/    # Decisions and roadmap snapshot
│   ├── requirements/  # Current and historical requirements
│   ├── sources/       # Recovered historical sources
│   └── verification/  # Traceability and open risks
├── .gitignore
└── README.md
```

The backend and frontend are separate applications in one repository.

The backend follows the approved modular monolith direction, with a single codebase intended to support independently running API and Worker process roles.

These separate runtime roles and the M01 business modules are not yet implemented.

## 4. Local Development

ASEP is developed on Ubuntu using a localhost-first environment.

### Prerequisites

- Java JDK 21
- Node.js 24
- Corepack
- pnpm 12.11.2

The backend uses Maven Wrapper, so a global Maven installation is not required.

### Backend

From the repository root:

```bash
cd backend

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

./mvnw spring-boot:run
```

Default backend address:

`http://127.0.0.1:8080`

Health endpoint:

`http://127.0.0.1:8080/actuator/health`

The backend is configured to bind to `127.0.0.1` by default.

### Frontend

In a separate terminal, from the repository root:

```bash
cd frontend

corepack pnpm install
corepack pnpm dev --hostname 127.0.0.1 --port 3000
```

Frontend address:

`http://127.0.0.1:3000`

The explicit hostname argument restricts the development server to localhost.

### Testing and Builds

Backend:

```bash
cd backend
./mvnw verify
```

Frontend:

```bash
cd frontend

corepack pnpm lint
corepack pnpm exec next typegen
corepack pnpm exec tsc --noEmit
corepack pnpm build
```

For complete setup instructions, tooling details, configuration conventions, and verification limitations, see the **[Local Development Guide](docs/development/LOCAL_DEVELOPMENT.md)**.

## 5. Architectural Direction

ASEP follows the architecture and technology decisions approved during M00.

| Area | Approved direction |
|---|---|
| Frontend | Next.js App Router + TypeScript; confidential OIDC BFF design |
| Backend | Java + Spring Boot + Spring Modulith |
| Architecture | Modular Monolith with separate API/Worker processes |
| Persistence | PostgreSQL durable relational state |
| Background execution | PostgreSQL-backed durable workflows and handoff |
| Identity | Self-hosted Keycloak; ASEP-managed business authorization |
| Authentication | OIDC Authorization Code + PKCE; Spring Security Resource Server |
| AI inference | Replaceable local-only inference port |
| Deployment | Single Ubuntu host; Docker Compose initial direction |

These are approved architectural and technology directions, not evidence that every component has been implemented or validated.

Exact scheduler, persistence schema, ORM/SQL boundaries, AI runtime/model, authentication session details, and deployment topology remain subject to their documented decisions and verification requirements.

### Business Module Boundaries

The M01 architecture identifies six logical business modules:

1. Identity & Access
2. Integration Management
3. MR Detection
4. Review
5. Publication
6. Evaluation

The architecture keeps Review and Publication responsibilities separate.

Review completion does not independently authorize GitLab publication.

Business modules will be introduced when their functionality is implemented, rather than scaffolding empty modules prematurely.

## 6. M01 Requirements and Safety Boundaries

The current M01 requirements incorporate the approved Requirements v0.2 / CR-001 and Viewer Global Full Read-Only Amendment.

Important requirements and architectural constraints include:

- GitLab API polling with revision-aware MR detection and review admission.
- Local-only AI inference without external AI providers by default.
- Evidence-backed findings with explicit coverage and provenance limitations.
- Shadow Mode without GitLab publication.
- Manual publication requiring exact Owner approval.
- Conditional Auto-Publish, disabled by default, requiring Owner-enabled policy and independent quality/safety gates.
- Backend-enforced Owner/Viewer authorization, including Viewer Global Full Read-Only within its approved data scope.
- Freshness validation before external publication.
- Publication Ledger and reconciliation for ambiguous GitLab write outcomes.
- Durable execution, bounded retries, cancellation, and recoverable failure states.
- Retention, credential security, and restricted network egress.
- No automatic GitLab MR approval or merge.

Source code, Merge Request content, model responses, and external tool outputs must be treated as untrusted inputs.

**These are requirements and design constraints. They must not be interpreted as controls already enforced by the current application foundation.**

## 7. Verification Status and Limitations

Initial M01-02 verification evidence includes:

| Verification | Observed result |
|---|---|
| Backend application context test | PASS |
| Spring Modulith architecture verification test | PASS |
| Backend Maven tests | 2 passed, 0 failures |
| Backend Maven `verify` | BUILD SUCCESS |
| Executable JAR packaging | PASS |
| Backend health endpoint | HTTP 200, status `UP` |
| Backend localhost binding | Verified |
| Frontend ESLint | PASS |
| Frontend TypeScript | PASS |
| Frontend production build | PASS |
| Frontend HTTP response | HTTP 200 |
| Frontend landing page | Manually confirmed |

The Spring Modulith architecture test currently verifies the foundational architecture inspection mechanism. It does not yet establish valid dependency boundaries between actual business modules.

A fresh-clone reproducibility test has not been independently verified.

The current foundation does not demonstrate end-to-end GitLab review, local AI inference quality, secure authentication, durable Worker recovery, or safe publication.

Architecture verification items V-01 through V-14 remain OPEN / NOT VERIFIED unless separately closed with accepted runtime evidence.

## 8. Documentation

On **2026-10-18**, the Project Owner accepted the consolidated repository documentation baseline created during M00-04, subject to its documented provenance, supersession, and verification limitations.

At the time of M00-04 acceptance, the repository contained documentation only. Application foundation development began afterward during M01-02.

| Document | Purpose |
|---|---|
| [Documentation Index](docs/INDEX.md) | Document authority, precedence, provenance, and source limitations |
| [Local Development Guide](docs/development/LOCAL_DEVELOPMENT.md) | Tooling, startup, tests, builds, and localhost configuration |
| [Current M01 Requirements](docs/requirements/M01_CURRENT_REQUIREMENTS.md) | Owner-accepted consolidation of effective requirements and amendments |
| [Historical M01 Scope v0.1](docs/requirements/v0.1/M01_MVP_SCOPE.md) | Historical approved requirements, partially superseded |
| [Technical Discovery Report](docs/discovery/TECHNICAL_DISCOVERY_REPORT.md) | Development environment and operational/security constraints |
| [System Architecture](docs/architecture/ARCHITECTURE.md) | Approved design direction and module/process boundaries |
| [ADR-001](docs/architecture/adr/ADR-001.md) | Modular Monolith with Separate Worker Processes |
| [ADR-002](docs/architecture/adr/ADR-002.md) | Durable Workflow and Publication Boundaries |
| [Decision Register](docs/governance/DECISIONS.md) | Architecture, module, and technology decisions |
| [Roadmap Snapshot](docs/governance/IMPLEMENTATION_ROADMAP.md) | Historical milestone direction; not live Project Control |
| [Traceability and Risks](docs/verification/TRACEABILITY_AND_RISKS.md) | Requirement mapping and outstanding verification work |
| [Recovered Source Inventory](docs/sources/recovered/README_SOURCE_INVENTORY.md) | Historical recovery evidence and missing-source limitations |

Historical approved content must be preserved. Reconstructed records and consolidated repository documents retain the provenance qualifications recorded in the documentation index.

## 9. Development and Governance

ASEP is developed incrementally using a milestone- and ticket-based workflow.

Engineering changes follow:

**Requirements → Investigation → Design → Trade-off Review → Implementation → Testing → Diff Review → Owner Approval → Commit**

Major architectural changes require explicit discussion and appropriate decision records.

Source-code quality, security, observability, recoverability, and reproducible tests take precedence over the number of frameworks or AI agents implemented.

Ticket completion and milestone status are managed separately through Project Control. The repository README and historical roadmap snapshot are not substitutes for live project status tracking.

The long-term goal is a working, maintainable, and verifiable AI engineering platform suitable for a professional software engineering portfolio.