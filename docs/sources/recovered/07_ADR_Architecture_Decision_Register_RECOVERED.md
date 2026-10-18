# 07 — ADR & Architecture Decision Register v0.1
**Owner accepted register structure:** 2026-10-18.
**Status:** Register consolidation APPROVED; detailed original ADR-001/002 files not recovered in accessible sources.
**Important:** Any reconstructed text below is not the verbatim original ADR proposal. ADR-003..007 remain document-structure DRAFTS, not signed architectural decisions.

## 1. Existing approved ADRs

### ADR-001 — Modular Monolith with Separate Worker Processes
- **Owner APPROVED:** 2026-10-16.
- **Decision:** One modular-monolith backend codebase with independent API and background Worker processes where justified; clear logical module boundaries, avoid premature microservices.
- **Evaluated alternatives:** single-process modular monolith; modular monolith with separate workers (chosen); independent microservices.
- **Rationale:** AI work is long-running; isolate process lifecycle while preserving single-developer operability and avoiding distributed-service operations.
- **Tradeoff:** two process roles, durable job coordination and resource/recovery engineering vs reduced API impact.
- **Original approval scope:** No backend framework, DB, queue, AI runtime or exact topology was approved in the original ADR. Spring Boot selected later in TSE-01.
- **Verbatim original:** NOT RECOVERED.

### ADR-002 — Durable Workflow & Publication Boundaries
- **Owner APPROVED:** consolidated decision recorded 2026-10-17, reaffirmed 2026-10-18.
- **Decision A:** GitLab API polling, revision-aware durable detection/checkpoints/admission.
- **Decision B:** atomic job claim, leases/fencing, recovery watchdog, cancellation, bounded retries.
- **Decision C:** durable Review→Publication handoff and independent Publication execution, serialized writes, minimal Publication Ledger, remote reconciliation for ambiguous effects.
- **Invariant:** No blind duplicate posts, no false success, no external exactly-once claim, no auto-merge, Publication checks revision/current Owner authorization policy before writes.
- **Tradeoff:** persistence and reconciliation complexity in exchange for recoverability.
- **Deferred:** Exact scheduler/library/Outbox schema, recovery budgets, GitLab API runtime verification. TSE-03 PostgreSQL-backed Outbox candidate was decided later.
- **Verbatim original:** NOT RECOVERED.

## 2. Approved module decisions
MD-01 Review vs Publication ownership; MD-02 GitLab read/write capabilities; MD-03 Review context/validation; MD-04 Identity & Access (Keycloak amendment); MD-05 Integration Management; MD-06 Thin Coordination & Handoff; MD-07 Evaluation; MD-08 Query Composition; MD-09 Domain History/Telemetry; MD-10 Data Lifecycle/Retention. Individual full text not recovered. See `03_Module_Decisions_MD01_MD10_RECOVERED.md`.

## 3. TSE direction register
TSE-01 Java/Spring Boot/Modulith approved.
TSE-02 PostgreSQL approved.
TSE-03 PostgreSQL-backed durable jobs+handoff approved direction; library/outbox PoC open.
TSE-04 Local inference abstraction approved; model/runtime PoC open.
TSE-05 Next.js App Router+TypeScript approved.
TSE-06A Keycloak approved.
TSE-06B OIDC/PKCE confidential Next.js BFF + Spring Resource Server direction approved.
TSE-06C Compose/single-host direction; Kubernetes/Jenkins future targets; Rancher optional; Redis only approved use case. See TSE recovered notes.

## 4. Proposed future formal ADR titles (NOT approved as final ADR documents)
- ADR-003 — Backend & Persistence (draft documentation proposal)
- ADR-004 — Frontend Architecture (draft documentation proposal)
- ADR-005 — Identity & Session Architecture (draft documentation proposal)
- ADR-006 — Local AI Integration (draft documentation proposal)
- ADR-007 — Deployment Strategy (draft documentation proposal)

**No action may treat these drafts as additional approved decisions.** Do not duplicate or contradict ADR-001/002; TSE-03 implementation documentation may be incorporated into ADR-002 rather than a redundant ADR.

## 5. Supporting approved baselines
- Requirements Baseline v0.2 + CR-001 + Viewer Global Full Read-Only Amendment (full originals missing).
- Minimal Persistence; RET-001..008; OPS-001..004; SEC-009.
- Testing & Observability Architecture v0.1 (2026-10-18 direction approval).
- Requirements Traceability & Risk/Verification Backlog v0.1 (2026-10-18 planning approval).
- Final System Architecture Baseline Review v0.1 explicitly Owner approved on 2026-10-18 at design level.

## 6. Provenance and restrictions
The original ADR-001 and ADR-002 long-form text and original section-by-section Owner approval messages have not been verified in this session. Decision approval != availability of full original. Do not tag new reconstructed ADR files as verbatim APPROVED DOCUMENT until source is verified or Owner explicitly accepts an updated reconstruction. Do not change roadmap or claim implementation verified.
