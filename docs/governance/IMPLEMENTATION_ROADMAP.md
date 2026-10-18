# ASEP - Implementation Roadmap & Project Control Snapshot

> **Repository-document status: OWNER-ACCEPTED HISTORICAL SNAPSHOT (2026-10-18; M00-04), NOT live Project Control status.**
> **Roadmap source:** Roadmap Baseline v0.1 later reported Owner APPROVED; original 2026-10-14 Project Control document was labeled **PROPOSED** at preparation time.  
> **Status evidence:** M00-04 handoff reported M00-01/02/03 COMPLETED and M00-04 READY; this is a **historical snapshot**, not a live Project Control update.  
> **Scope:** Record approved milestone direction and known historical statuses; do not create new tickets/assignments or claim M01 implementation.

## 1. Vision and milestones (Roadmap v0.1)

ASEP is a portfolio-quality, self-hosted Agentic Software Engineering Platform. Its long-term flow is:

`Jira Ticket -> Investigation -> Planning -> Human Approval -> Implementation -> Testing -> GitLab Draft MR -> AI Code Review -> Human Merge`

| Milestone | Title | Outcome and expected evidence at a high level |
|---|---|---|
| **M00** | Architecture & Foundation | Project vision/requirements, technical discovery, security policies, ADRs, approved architecture and version-controlled repository foundation |
| **M01** | AI Merge Request Reviewer | GitLab MR detection/review, local AI findings, controlled publication, meaningful reliability/security tests and independent AI evaluation |
| **M02** | Jira Ticket Investigation | Jira ticket ingestion, repository/context investigation, planning/reporting and human approval |
| **M03** | Autonomous Implementation | Approved bounded code edits, isolated execution/testing and GitLab Draft MR creation |
| **M04** | Self-Correction & Orchestration | Robust multi-step workflows with retries, cancellation, idempotency, recovery and observability maturity |
| **M05** | Production Readiness & Portfolio | CI/CD, reproducible deployment, verifiable quality/security evidence, portfolio demos and engineering case studies |

These are Roadmap v0.1 milestone intents, not a claim that later milestones are started or completed. Technical safety and testability begin in earlier milestones; they are not postponed until M04/M05.

## 2. Dependencies (logical, not a new approval)

- **M00** establishes the initial foundation needed for implementation planning.
- **M01** and **M02** supply independent initial product workflows.
- **M03** builds on GitLab review and Jira investigation after those capabilities have actual evidence.
- **M04** hardens orchestration and failure recovery across implemented workflows.
- **M05** demonstrates repeatable operations and portfolio evidence; it is not permission to defer M01 safety requirements.

Additional tickets, cross-milestone reallocation and concrete dates require separate Project Control/Owner approval.

## 3. Recorded M00 ticket status (source: M00-04 handoff)

| Ticket | Description | Latest **evidenced at handoff** | Limitation |
|---|---|---|---|
| M00-01 | Project Vision & MVP Scope | **COMPLETED** | Historical approved v0.1; later requirements amendments tracked separately |
| M00-02 | Technical Discovery & Architecture Constraints | **COMPLETED** | Approved TDR; real API/GPU/runtime work remains NOT VERIFIED |
| M00-03 | System Architecture & ADR Baseline | **COMPLETED** | Architectural design approved, not implementation |
| M00-04 | Repository Foundation & Documentation Adoption | **READY** | Owner-approved scope; **this document bundle does not change official Project Control status** |

The user's statement that a directory structure exists is not equivalent to repository initialization/commit verification. Branch/remote existence, changes actually applied to the local repository and latest ticket status are **not inspected** in this package.

## 4. M00-04 boundary and exit evidence

**In scope:** source inventory; version-controlled original requirements and discovery; effective-requirements precedence; architecture decisions and module ownership; documented security/retention and open risks; a clear index/provenance register; repository hygiene; Owner-reviewed Git diff and acceptance evidence.

**Out of scope:** Spring Boot/Next.js application bootstrap; database and Keycloak deployment; implementing GitLab polling or Auto-Publish; runtime local-model integration; tests or CI; automatic changes to Project Control statuses; pushing to a remote without explicit authorization.

Potential M00-04 exit evidence (documentation planning checklist, **not independently approved ticket IDs**):

- Original v0.1 documents retained accurately, later amendments explicitly linked.
- Recovered sources labeled; new authoritative consolidation accepted by Owner after checking open source gaps.
- Architecture and decision register do not backdate later technology choices into earlier ADRs.
- V-01..V-14, deferred choices and policies remain traceable and open until evidence.
- Actual repository tree, `git status`, `git diff --check`, secret scan, staged diff and commit output inspected.
- Execution Summary prepared for Project Control to decide official completion.

## 5. Deferred/platform learning direction

- **M01 runtime direction:** Java Spring Boot/Modulith, Next.js, PostgreSQL durable workflow, Keycloak, local model inference; single Ubuntu host, Docker Compose initial candidate.
- **Learning targets:** K3s/Kubernetes and Jenkins may be introduced in separately approved future work. Rancher is optional. Their mention is not a mandatory M01 dependency or automatic milestone reallocation.
- **Optional infrastructure:** Redis/RabbitMQ require concrete need, security review and approval; PostgreSQL-backed execution remains the current direction.
- **Open feasibility:** GitLab runtime/PAT, local inference quality, GPU passthrough, process/credential isolation, publication race conditions, backup and TTL enforcement.

## 6. Governance workflow

1. Project Control & Roadmap is the authority for milestone/ticket tracking and approved scope.
2. Execute individual tickets in dedicated conversations and record evidence and Owner decisions.
3. Keep Architecture Decision Records for major choices, with explicit provenance, alternatives, trade-offs and consequences.
4. After execution, send an Execution Summary to Project Control. Do not auto-update milestone status from a working conversation or document bundle.
5. Never claim an implementation is finished merely because design documents are approved.

## 7. Source and temporal accuracy

- [Recovery of original roadmap + ticket snapshot](../sources/recovered/08_Implementation_Roadmap_Project_Control_RECOVERED.md).
- [M00-02 Technical Discovery](../discovery/TECHNICAL_DISCOVERY_REPORT.md) - confirmed environment constraints, not current machine runtime proof.
- [Current requirements consolidation](../requirements/M01_CURRENT_REQUIREMENTS.md) - current decisions, Owner-accepted repository consolidation; source gaps still recorded.
- [Traceability & Risks](../verification/TRACEABILITY_AND_RISKS.md) - unresolved validation obligations.

**No currently authenticated live Project Control/Jira snapshot was retrieved.** This file must not be presented as a newly Owner-signed or dynamically updated Implementation Roadmap authority document.
