# 08 — Implementation Roadmap v0.1 & Project Control Snapshot
**Provenance:** `Pasted markdown(20261014-152242).md` (original **PROPOSED** baseline text 2026-10-14), plus later approved Project Control/Execution handoff `Pasted text(7).txt` (M00-04 READY). This is a historical snapshot, NOT a live Jira/Project Control query.
**Known Owner-approved:** Roadmap v0.1 (M00..M05) retains its milestone names; additions and ticket reallocation require separate approval.

## 1. Original roadmap milestones
| Milestone | Name | Original responsibility and exit evidence |
|---|---|---|
| M00 | Architecture & Foundation | Vision, MVP requirements, architecture, security, standards, ADRs, repository foundation; approved design before M01 |
| M01 | AI Merge Request Reviewer | GitLab MR review, structured evidence, controlled publication, errors/tests and AI evaluation |
| M02 | Jira Ticket Investigation | Jira ingestion, repository investigation, report/plan, human approval |
| M03 | Autonomous Implementation | Isolated code editing/testing and Draft MR creation after approval |
| M04 | Self-Correction & Orchestration | Robust multi-step workflow, retries, cancellation, idempotency, recovery, observability |
| M05 | Production Readiness & Portfolio | CI/CD, test/security evidence, repeatable deployment, demos and case study |

These definitions are from the initial 2026-10-14 roadmap **PROPOSED** document. Later approval of Roadmap Baseline v0.1 is recorded in ASEP governance; the historical document's header must not be silently edited to APPROVED.

## 2. Logical milestone dependencies
M00 foundation; M01 and M02 provide integration workflows; M03 depends on M01 and M02; M04 improves proven workflows from M01–03; M05 requires quality/verification evidence across M00–04. Security/testing/reliability start before M04/M05.

## 3. Latest evidenced ticket snapshot (from M00-04 handoff)
| Ticket | Evidence-based status | Source / limitation |
|---|---|---|
| M00-01 Project Vision & MVP Scope | COMPLETED | M00-04 handoff; requirements v0.1 approved 2026-10-15 |
| M00-02 Technical Discovery & Architecture Constraints | COMPLETED | M00-04 handoff; discovery approved 2026-10-16 |
| M00-03 System Architecture & ADR Baseline | COMPLETED | M00-04 handoff; final design signoff 2026-10-18 |
| M00-04 Repository Foundation & Documentation Adoption | READY at handoff creation | Owner-approved scope, dependencies M00-01..03, not automatically in progress/completed |

The M00-03 Execution Summary written immediately after approval recommended `COMPLETED after Project Control acceptance`, whereas the **later M00-04 handoff** reports M00-03 already COMPLETED. Preserve chronology rather than treating the older summary's unupdated status as current.
**Other ticket/milestone official statuses:** No current live Project Control/Jira output is available here. Do not infer that M00 overall or M01–M05 have changed status simply from the original PLANNED placeholders.

## 4. Roadmap and governance rules
- One Project Control & Roadmap conversation is source for ticket/milestone execution tracking.
- Milestone/ticket implementation takes place in dedicated execution conversations.
- Owner explicitly reviews new tickets, scope and milestone allocation; handoffs include evidence and Execution Summary.
- No auto-change to approved roadmap or ticket statuses from this recovery pack.
- M00-04 is documentation/repository foundation ONLY, not Spring Boot/Next.js bootstrap, GitLab integration or infrastructure deployment.

## 5. DevOps direction introduced after initial roadmap approval
Docker Compose initial candidate; Kubernetes/K3s and Jenkins planned DevOps learning targets; Rancher optional; Redis only a separately approved, benchmarked use case. These are **not automatically required M01 runtime dependencies** or approved milestone changes.

## 6. Deferred work and OPEN verification
V-01 GitLab credential/API; V-02 polling; V-03 stale race; V-04 remote duplicate write; V-05 fencing/outbox recovery; V-06 Auto-Publish gates; V-07 AI quality; V-08 context truncation; V-09 Keycloak/JWT/authz; V-10 secrets/egress/injection; V-11 retention; V-12 GPU; V-13 process isolation; V-14 backup/ledger expiration. All remain OPEN/NOT VERIFIED without test evidence.

## 7. Original roadmap source is available for citation/navigation
The original `Pasted markdown(20261014-152242).md` contains full milestone definitions, proposed initial ticket M00-01, historical source-of-truth document list and original approval questions. It was labeled `Baseline v0.1 — Proposed` at 2026-10-14. Do not pass the recovered excerpt as a complete signed modern Implementation Roadmap authority file.

## 8. Pending source of truth artifacts
An actual currently approved `IMPLEMENTATION_ROADMAP.md`, current Jira/Project Control ticket status export, exact M00-04 implementation progress, current branch/repo and approved changes after handoff are **NOT VERIFIED**.
