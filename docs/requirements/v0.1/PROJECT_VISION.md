# ASEP — Project Vision Baseline v0.1

> **Status:** APPROVED — Requirements Baseline v0.1 (explicit Owner sign-off on 2026-10-15); PROPOSED items remain non-binding  
> **Ticket:** M00-01 — Project Vision & MVP Scope  
> **Roadmap:** v0.1 — APPROVED (milestones unchanged)  
> **Prepared:** 2026-10-15  
> **Document role:** Owner-approved requirements baseline, ready for repository adoption after repository verification.  
> **Repository/file existence:** NOT VERIFIED. This is an Owner-approved standalone baseline copy, **not** a repository change.

## 1. Vision

**ASEP (Agentic Software Engineering Platform)** is an enterprise-inspired, self-hosted platform that applies AI to **selected** Software Development Lifecycle (SDLC) tasks through controlled workflows. The platform emphasizes verifiable technical findings, secure handling of source code, traceable execution, and explicit human approval of consequential actions.

**Long-term target workflow:**

`Jira Ticket → Investigation → Planning → Human Approval → Implementation → Testing → GitLab Draft MR → AI Code Review → Human Merge`

This is a **long-term product vision**, not the first MVP's feature list. ASEP is a personal engineering learning and portfolio project, not an existing production service or multi-tenant SaaS.

## 2. Problem Statement

Reviewing a merge request (MR) can require developers to inspect changes, understand surrounding code, reason about regressions, and identify security or reliability risks. Repeated manual review consumes time and may miss issues. An AI reviewer can help surface technically grounded risks, but may produce false positives and cannot be trusted as the final authority.

**Problem ASEP M01 addresses:** enable a developer to request a review of an authorized GitLab MR, inspect evidence-backed AI findings, and publish only approved feedback to GitLab, while retaining human responsibility for accepting changes and merging.

## 3. Product Objectives

1. Deliver a demonstrably working GitLab AI code-review workflow, rather than a UI-only AI demo.
2. Improve review assistance through structured, actionable and verifiable findings; measure correctness rather than comment volume.
3. Keep sensitive repository context in a user-controlled, self-hosted AI inference environment.
4. Enforce authorization, human approval, stale-review protection, and traceable outcomes.
5. Develop transferable engineering skills in full-stack development, backend architecture, AI engineering, system design, testing, DevOps, and platform engineering.
6. Produce portfolio evidence: real workflows, tests, evaluation results, architecture rationale, and failure-handling demonstrations.

## 4. Target Users

### 4.1 Primary persona — Individual Developer / Repository Maintainer [CONFIRMED; REQ-001]

An individual maintainer operates ASEP, selects GitLab MRs, initiates reviews, inspects AI results, selects findings, approves publication, and manages integrations.

### 4.2 Owner and Viewer access [CONFIRMED; REQ-008, REQ-010]

- **Owner:** creates or disables accounts, controls GitLab integrations and repository access, triggers review, approves and publishes feedback.
- **Viewer:** can sign in and view shareable review summaries and **Owner-approved/published findings only**, for repositories/projects explicitly granted by the Owner; cannot trigger reviews, manage integrations/accounts, approve findings, or publish. Approved findings not yet published must display their actual publication status.
- **Owner visibility:** Owner can inspect all findings, including pending, rejected and unpublished findings. Backend checks enforce the same boundary as the Web UI (REQ-010).
- **MVP constraint:** two simple roles, not an organization/multi-tenant authorization product. Published comments on GitLab follow GitLab access permissions, separately from ASEP dashboard permissions.

## 5. M01 — First MVP Value Proposition

**A secure, human-controlled AI Merge Request Reviewer** accessed primarily through a Web UI [CONFIRMED direction]. Owner starts a review manually [REQ-002], ASEP retrieves authorized diff and relevant context, local AI analyzes risk-related changes [REQ-004, REQ-009], and the Web UI presents evidence-backed actionable findings [REQ-005]. Owner selects which findings to publish [REQ-003, REQ-006]. ASEP must block publication when the reviewed MR revision is stale [REQ-007].

A single current GitLab summary comment per MR is maintained and updated **only after** Owner approves a publication for a new review [PUB-001]. Existing inline discussions are kept; ASEP keeps the complete review-run history in the authorized dashboard.

**Phase after the manual MVP:** GitLab webhook/event-driven auto-review may be added within M01 once the manual workflow is validated. It is **not** part of the first MVP acceptance gate. Automatic review must not imply automatic publication.

## 6. Product Principles / Invariants

- **Human controls publication and merge:** AI may recommend; only authorized people decide what feedback is published. ASEP never auto-approves or auto-merges MRs.
- **Source-code confidentiality:** AI inference on source-code context is local-only in M01; no source-code context is sent to external AI inference providers. GitLab connectivity remains necessary for legitimate MR operations.
- **Least privilege:** authenticated accounts and repository-scoped authorization; access is denied by default absent a grant.
- **Untrusted inputs:** MR descriptions, code, comments and tool output are data, not instructions that can override ASEP policy.
- **Revision awareness:** findings and Owner approvals are bound to the reviewed MR version; stale results cannot be newly published.
- **Accuracy over volume:** emphasize logic bugs, security, reliability and regressions; do not manufacture findings or claim full coverage when context is incomplete.
- **Recoverability and transparency:** failures, partial GitLab publication, and execution status must be inspectable; do not report false success.
- **Architectural restraint:** architecture, framework selection, database schema, model runtime, and infrastructure choices remain subject to later M00 decisions.

## 7. Confirmed Scope Boundaries

**M01 initial MVP includes:** Web UI for Owner/Viewer; Owner-created accounts and repository-scoped viewing; manual GitLab MR review; diff and relevant context retrieval; local-only risk-focused AI analysis; structured findings; preview, selective approval, freshness validation, GitLab publication after approval; one summary per MR; review and publication history; essential security/reliability behavior.

**M01 initial MVP excludes:** webhook-triggered automatic reviews; Jira ticket processing; autonomous implementation; modifying repository code; running arbitrary AI-generated commands; automatic MR approval/merge; complex multi-agent orchestration; full engineering analytics; multi-tenant SaaS; general style-only review.

## 8. Roadmap Baseline (Approved v0.1)

| Milestone | Responsibility |
|---|---|
| M00 — Architecture & Foundation | Define vision, requirements, architecture, security and engineering baseline |
| M01 — AI Merge Request Reviewer | Implement and evaluate the first controlled AI review workflow |
| M02 — Jira Ticket Investigation | Expand into ticket investigation |
| M03 — Autonomous Implementation | Approved and constrained implementation workflow |
| M04 — Self-Correction & Orchestration | Recovery and orchestration capability |
| M05 — Production Readiness & Portfolio | Reliability, demo and portfolio evidence |

No milestone is renamed, added, removed or marked complete by this requirements document.

## 9. Success Definitions

### M00-01 requirements deliverable (OWNER APPROVED; PROJECT CONTROL HANDOFF PENDING)

Completion requires approved Project Vision and M01 scope; clear in/out boundaries; testable functional, security and reliability criteria; failure handling; evaluation approach; assumptions/open questions; and explicit Owner approval. M00-01 does **not** require working application code or completed model benchmarks.

### M01 MVP (future execution)

Demonstrate at least one complete manual GitLab MR review on a real test MR. Verify access control, approval, stale protections, publishing and failure paths. Establish reproducible local AI quality measurement and meet **quality thresholds agreed after a real benchmark** (baseline-first; EVAL-001). A measurement report alone does not prove AI quality is acceptable.

## 10. Constraints, Dependencies and Risks

| Item | Status | Implication |
|---|---|---|
| Personal self-hosted Ubuntu environment | Intended direction; capacity NOT VERIFIED | Performance thresholds need measurement |
| GitLab account, token, MR access and comments permissions | NOT VERIFIED | Must test the actual GitLab setup |
| Jira Free | Future integration; NOT VERIFIED | Not a blocking dependency for first M01 MVP |
| Local AI runtime/model quality and throughput | NOT VERIFIED | AI quality targets deferred until benchmark |
| Repository confidentiality, prompt injection, secret leakage | Known risk | Require security controls and tests |
| GitLab partial writes and concurrent MR updates | Known integration risk | Publication must be reconcilable; cannot assume atomicity |
| Viewer dashboard access | CONFIRMED (REQ-010) | Shareable summaries + approved/published findings in granted repositories only; Owner sees all findings |
| Retention/deletion policy | OPEN security detail | Define bounded retention and deletion behavior before storing real sensitive repository data |

### GitLab capability evidence (public documentation; environment still unverified)

- Notes API — MR note creation and update: https://docs.gitlab.com/api/notes/
- Discussions API — diff-anchored review comments: https://docs.gitlab.com/api/discussions/
- MR API — diff references and versions: https://docs.gitlab.com/api/merge_requests/
- Quick actions — commands embedded in comments require defensive publication handling: https://docs.gitlab.com/user/project/quick_actions/
- GitLab.com project access token subscription restrictions: https://docs.gitlab.com/user/project/settings/project_access_tokens/

## 11. Approved Policy Packages / Handoff

**[CONFIRMED 2026-10-15]** Owner approved the M00-01 GitLab Publication, Security and Reliability policy packages. These include approved-only summary/inline publication, no unapproved fallback, one summary per MR, per-finding partial-failure tracking, safe retries and quick-action defense; Owner-managed accounts, default-deny repository access, local-only AI and protected credentials; bounded retries, cancellation, traceable failures and configurable performance budgets.

**Performance values are provisional configuration defaults, not approved production SLOs.** They must be verified using actual local hardware/model benchmarks. Risk Register v0.1 was approved as a tracking/handoff register on 2026-10-15; all listed risks remain OPEN and all unverified dependencies remain NOT VERIFIED. Full Project Vision + M01 scope baseline received explicit Owner approval on 2026-10-15; all OPEN risks and NOT VERIFIED assumptions retain those statuses.

## 12. Authority / Handoff

The project owner is the final decision-maker. This approved requirements baseline does **not** finalize the candidate technology stack (Next.js, NestJS, PostgreSQL, Redis/BullMQ, Ollama, etc.), define concrete system architecture, or claim any repository state. Once approved, content may become authority material; architecture, security and implementation tickets should reference the approved baseline without silently modifying it.

**Approval:** Full M00-01 requirements baseline APPROVED by Owner on 2026-10-15, including this document and `M01_MVP_SCOPE.md`; items labeled PROPOSED remain non-binding.  
**M00-01 status:** Eligible to propose COMPLETED in Execution Summary after Owner sign-off; Project Control status is not changed automatically.