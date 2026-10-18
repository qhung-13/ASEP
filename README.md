# ASEP - Agentic Software Engineering Platform

ASEP is a personal, self-hosted engineering project exploring reliable AI-assisted Software Development Lifecycle (SDLC) workflows. The first implementation milestone (M01) is an AI Merge Request Reviewer for GitLab.

**Current repository purpose:** M00-04 Repository Foundation & Documentation Adoption.

**Documentation acceptance:** On 2026-10-18, the Owner accepted the consolidated repository documentation baseline, subject to the provenance, supersession, and OPEN/NOT VERIFIED limitations recorded in [docs/INDEX.md](docs/INDEX.md). This package establishes documentation only. It does not contain a working application, deployed integrations, tested AI inference, or a completed M01.

## Product vision

Long-term target workflow:

`Jira Ticket -> Investigation -> Planning -> Human Approval -> Implementation -> Testing -> GitLab Draft MR -> AI Code Review -> Human Merge`

For M01, ASEP detects GitLab MR revisions, performs local-only AI review, presents evidence-backed findings in a Web UI, and controls GitLab publication through explicit policy and safety boundaries. Review completion must never, by itself, authorize a remote write. ASEP never automatically approves or merges merge requests.

## Documentation entry points

| Read this | Purpose |
|---|---|
| [Document index](docs/INDEX.md) | Document authority, status, precedence and provenance |
| [Current M01 requirements](docs/requirements/M01_CURRENT_REQUIREMENTS.md) | Owner-accepted repository consolidation of approved requirements/amendments; original CR-001/Viewer full texts still missing |
| [Historical requirements v0.1](docs/requirements/v0.1/M01_MVP_SCOPE.md) | Owner-approved 2026-10-15 record; portions superseded |
| [Technical Discovery](docs/discovery/TECHNICAL_DISCOVERY_REPORT.md) | Owner-approved 2026-10-16 discovery and operational constraints |
| [System architecture](docs/architecture/ARCHITECTURE.md) | Owner-accepted repository consolidation of approved architecture design (design level only) |
| [ADR-001](docs/architecture/adr/ADR-001.md) and [ADR-002](docs/architecture/adr/ADR-002.md) | Approved architectural decisions with explicit provenance limits |
| [Decision register](docs/governance/DECISIONS.md) | ADR/MD/TSE decisions, approval scopes and deferrals |
| [Roadmap snapshot](docs/governance/IMPLEMENTATION_ROADMAP.md) | Approved milestone direction; status snapshot is not live Project Control |
| [Traceability and risks](docs/verification/TRACEABILITY_AND_RISKS.md) | Requirements -> modules -> verification, open V-01..V-14 |
| [Recovered source inventory](docs/sources/recovered/README_SOURCE_INVENTORY.md) | Source recovery evidence, not a substitute for missing originals |

## Architectural direction (design-level approval)

- **Frontend:** Next.js App Router and TypeScript; confidential OIDC BFF design.
- **Backend:** Java, Spring Boot and Spring Modulith; modular monolith with independently running API and Worker process roles.
- **Persistence and durable execution:** PostgreSQL, durable workflow state and publication ledger; PostgreSQL-backed jobs/outbox direction. Exact scheduler and SQL/ORM choices deferred.
- **Identity:** self-hosted Keycloak; ASEP enforces business authorization.
- **AI:** replaceable local inference port; model/runtime selection and GPU viability not verified.
- **Deployment:** one Ubuntu host; Docker Compose initial candidate; no public Web UI by default.

These are architecture and technology direction decisions, **not** evidence that any runtime or security control has been implemented.

## Safety boundaries

- Source code and MR content are untrusted; no external AI inference by default.
- Auto-Publish is **disabled by default**, controlled by Owner-enabled repository policy and independent quality/safety gates.
- Shadow Mode makes no GitLab writes. Manual publication still requires exact Owner approval.
- GitLab writes require revision, policy, authorization and content checks; ambiguous results are reconciled rather than blindly retried.
- Minimal persistence and retention apply (RET-001..008, OPS-001..004, SEC-009).
- Viewer has Global Full Read-Only access to retained, sanitized ASEP-managed business data but no mutations or raw secrets.

## Development and governance

This documentation bundle is a **prepared artifact**, not evidence that the user's local working tree was modified or that files were reviewed/committed. Before committing, the Owner should review [the document index](docs/INDEX.md), compare the diff, validate secrets hygiene and explicitly accept the newly consolidated documents.

No application scaffold or CI pipelines are introduced as part of this documentation package. See [the roadmap snapshot](docs/governance/IMPLEMENTATION_ROADMAP.md) for the approved milestone direction.
