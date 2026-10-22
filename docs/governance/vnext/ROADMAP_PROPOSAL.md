# ASEP vNext — Owner-Selected Execution Priority (Ticket Allocation Pending)

**Prepared:** 2026-10-22
**Status:** P-01 PRIORITY OWNER ACCEPTED (2026-10-22); work-package sequencing is a documentation baseline, while new ticket IDs, official milestone reallocation and live execution statuses remain Project Control pending.
**Source:** 2026-10-22 accepted P-01/P-02/P-03 directions and existing M00–M05 roadmap snapshot.
**No implementation/PoC began while creating this proposal.**

## 1. Preserve historical authority

Original approved roadmap intent is:

- M00 Architecture & Foundation
- M01 AI Merge Request Reviewer
- M02 Jira Ticket Investigation
- M03 Autonomous Implementation
- M04 Self-Correction & Orchestration
- M05 Production Readiness & Portfolio

The historical roadmap remains unchanged. vNext proposes prioritizing a **cross-capability vertical slice**, not rewriting milestones as if they were never approved. New formal ticket IDs and statuses are NOT created by this document.

## 2. Known current-work evidence and caveats

- Repository foundation and documentation baseline were Owner-accepted in earlier conversations; verify live `git log`, `git status` and file status before adoption.
- Prior work reported M01-02 scaffolding completed; M01-03 GitLab Read Adapter had progressed on a feature branch and was subsequently paused for architecture research. **Do not infer merge to `main` or current tests from that report.**
- M01-01 GitLab feasibility may have unresolved review/verification items; verify Project Control before assigning a new status.
- No M01 ticket is silently cancelled, renamed, merged, moved or marked complete here.

## 3. Work package sequence — design baseline, not issued ticket IDs

| Proposed work package | Outcome/gate | Depends on | Out-of-scope guard |
|---|---|---|---|
| **WP-DOC** — Documentation adoption | Owner-reviewed vNext requirements, CR, ADRs, tests and approved execution plan committed as one consistent change set | Current docs/source provenance and Project Control check | No PoC/code before adoption |
| **WP-CONTRACT** — Runtime/identity contracts | Versioned Java↔Go↔Python API and permissions design, module ownership and migrations | WP-DOC | Avoid broad API framework and extra broker |
| **WP-FEAS** — Local AI/sandbox feasibility | Pinned local model/OpenHands, agent/verifier sandbox isolation, protected-oracle checks, cleanup and bounded failure evidenced on synthetic fixtures | **WP-CONTRACT minimum trust/task/artifact rules**; WP-DOC | No real project token/raw sensitive source; controller must not expose credentials to candidate processes |
| **WP-ORCH** — Go durability baseline | Minimal PostgreSQL queue, run/attempt/lease/fencing/recovery with fault-injection proof | WP-DOC, WP-CONTRACT | No River/Temporal unless validated need |
| **WP-INTEGRATE** — Trusted Jira/GitLab boundaries | Jira read policy/test, two-step push→Draft MR ledger, CI safe-enrollment and revocation/UNKNOWN tests | **WP-CONTRACT**, scoped Jira policy/credential gates, relevant sandbox/artifact evidence | No real remote write before CI/secret safety and authority checks; no auto merge/approve/comment writes via agent |
| **WP-SLICE** — Verified Jira→Draft MR | One ticket/repository, one active run, sealed candidate, independent tests/review, controlled Draft MR and honest CI observation | **WP-CONTRACT + WP-FEAS + WP-ORCH + WP-INTEGRATE minimum gates** | Block unsafe/unverified flows; no claims of CI green/merged without external confirmation |
| **WP-HARDEN** — Extended repeatability evidence | Broader fixtures, load/resilience depth, prolonged cleanup/restore drills and operational maturity | WP-SLICE | **Foundational** isolation, cancellation, fencing, UNKNOWN/CI safety are mandatory **before WP-SLICE**, not postponed here |

The table is a dependency proposal, **not** a commitment to sequential calendar dates or a declaration that any package is started. Decompose work into small tickets only when approved. One main implementation issue at a time.

### Execution gates (do not confuse with docs adoption)

1. **Docs gate:** corrected package, reviewed live Git diff, owner accepted docs; runtime still NOT VERIFIED.
2. **Contract/synthetic gate:** service identities, bounded profiles, OpenHands/tool vs supervisor placement, independent verifier sandbox and protected test oracle; no real tokens.
3. **Feasibility/durability gates:** local-model+GPU quality measurement; PostgreSQL atomic claim/lease/fencing/cancel/outbox failure tests. These can be investigated separately after shared contracts.
4. **Real-data gate:** explicit Owner Jira egress/data-classification/retention/enrollment authority; no silent extension of existing Viewer grants.
5. **Privileged delivery gate:** verified candidate vs pushed head, remote branch and MR sub-operation ledger, GitLab CI enrollment/secret policy, safe MR text, revocation/UNKNOWN reconciliation.
6. **Slice gate:** confirmed Draft MR with independent evidence, honest CI state, no unauthorized merge/approval; failure and recovery path demonstrated.

A failed prerequisite blocks its next *relevant* operation. Do not interpret all future VNX-V01..15 cases as already passed or as requirements for writing the initial synthetic PoC harness.

## 4. Reuse/migration policy

| Existing capability | Default proposal |
|---|---|
| Next.js/Spring Boot scaffold | Reuse, do not rewrite for language uniformity |
| GitLab Read Adapter | Investigate reuse as Spring-owned integration read capability |
| Keycloak/authorization designs | Preserve Owner/Viewer controls, don't bypass backend authorization |
| PostgreSQL direction | Retain engine; design domain-owned vNext schema without rewriting old data blindly |
| M01 Review/Publication business rules | Preserve in existing scope; no silent Auto-Publish activation or Publication Ledger migration |
| Java background worker assumptions | Review compatibility; Go orchestration introduced only after ADR adoption |
| M01 historical requirements/ADRs | Preserve texts/status/provenance; supersede only explicit scope of new approved change |

## 5. Project Control mapping required before ticket edits

- Which existing M01 tickets are paused vs resumed vs reframed? This cannot be inferred solely from old file snapshots.
- What identifier/phase will hold WP-DOC through WP-HARDEN? Avoid inventing M06 or repurposing M01/M02 without Owner agreement.
- Which existing GitLab adapter implementation/tests are on `main` vs a branch? Validate via Git.
- Which vNext outcomes must include CI feedback/rework in the first acceptance gate vs later hardening?
- New data retention/visibility policy and authorization review timing before using real Jira/repo data.

## 6. Done means evidence, not design prose

`Docs adopted` requires explicit Owner approval + reviewed Git diff/commit. `PoC passed` requires reproducible logs and test results. `Draft MR created` requires confirmed remote state. `CI passed` and `merged` are independent externally observed statuses. No status change without evidence.

## 7. Next allowed work

Current allowed step: apply the **single Owner-directed docs update** to the live repository and review/record its diff. After adoption, Project Control maps work packages into explicit tickets; then begin the first authorized PoC/implementation ticket in its own conversation. No installation/code/CI changes are authorized merely by this document.
