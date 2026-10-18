# ASEP Documentation Index and Authority Rules

**Prepared for:** M00-04 Repository Foundation & Documentation Adoption  
**Prepared:** 2026-10-18  
**Status of this index:** PROPOSED DOCUMENTATION ADOPTION; Owner review required before it becomes repository governance authority.  
**Important:** Status assigned to a *source decision* is different from approval of a newly written *repository document*.

## 1. Document types and provenance

| Type | Meaning | Handling |
|---|---|---|
| Historical approved source | Original or text-extracted approved content from M00-01/M00-02 | Preserve content; do not backdate amendments or rewrite it silently |
| Approved decision; recovered text | Decision was approved; original long-form record may be unavailable | Keep provenance and explicit missing-original note |
| Consolidated adoption draft | New repository representation assembled from historical/recovered sources | **PENDING OWNER REVIEW**; not equivalent to historical approved artifact |
| Historical roadmap snapshot | Earlier project plan or status as of a recorded handoff | Not live Project Control; do not infer ticket status changes |
| Deferred / not verified | Unresolved technology, risk, PoC, or implementation claim | Remains open until separately approved/tested |

## 2. Documentation register

| Path | Contents | Source/authority status | Repository-document status |
|---|---|---|---|
| [requirements/v0.1/PROJECT_VISION.md](requirements/v0.1/PROJECT_VISION.md) | M00-01 Project Vision v0.1 | Owner approved 2026-10-15; **historical** | Reproduced from Project Library text extraction; raw-byte identity not verified |
| [requirements/v0.1/M01_MVP_SCOPE.md](requirements/v0.1/M01_MVP_SCOPE.md) | REQ/PUB/EVAL and legacy acceptance criteria | Owner approved 2026-10-15; **partly superseded** | Reproduced from Project Library text extraction; raw-byte identity not verified |
| [requirements/M01_CURRENT_REQUIREMENTS.md](requirements/M01_CURRENT_REQUIREMENTS.md) | CR-001, Viewer amendment, effective requirement mapping | Underlying decisions approved 2026-10-17 | **NEW CONSOLIDATION DRAFT**; original CR-001/amendment full text missing |
| [discovery/TECHNICAL_DISCOVERY_REPORT.md](discovery/TECHNICAL_DISCOVERY_REPORT.md) | M00-02 technical constraints, RET/OPS/SEC-009 | Owner-approved report 2026-10-16 | Copied from user-provided complete report; still historical wrt later amendments |
| [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) | System design and module boundaries | Final architecture design approved 2026-10-18 | **NEW CONSOLIDATION DRAFT** |
| [architecture/adr/ADR-001.md](architecture/adr/ADR-001.md) | Modular monolith, separate workers | ADR-001 decision approved 2026-10-16 | Reconstructed from Owner-provided account; original not independently verified |
| [architecture/adr/ADR-002.md](architecture/adr/ADR-002.md) | Durable workflow and publication boundaries | ADR-002 decisions approved 2026-10-17 | Copied from *reconstructed consolidated* ADR-002 supplied by Owner |
| [governance/DECISIONS.md](governance/DECISIONS.md) | ADRs, MD-01..10, TSE-01..06C, deferrals | Underlying individual decisions approved to specified scopes | **NEW REGISTER DRAFT** |
| [governance/IMPLEMENTATION_ROADMAP.md](governance/IMPLEMENTATION_ROADMAP.md) | M00..M05 and reported ticket status | Roadmap v0.1 approved; original PROPOSED draft exists | **NEW ROADMAP SNAPSHOT DRAFT**, not live status |
| [verification/TRACEABILITY_AND_RISKS.md](verification/TRACEABILITY_AND_RISKS.md) | Requirement mapping, acceptance/rebaseline and V-01..14 | Planning baseline approved 2026-10-18 | **NEW CONSOLIDATION DRAFT**; no test results |
| [sources/recovered/](sources/recovered/README_SOURCE_INVENTORY.md) | Eight recovered areas and recovery README | Evidence/context, not the full original approved texts | Preserved source pack |

## 3. Effective requirements precedence

1. The full historical **Requirements Baseline v0.1** remains preserved unchanged.
2. **CR-001 / Requirements v0.2** supersedes contradictory manual-only and no-auto-publication rules. Approved automatic detection uses GitLab API polling and revision-aware admission; Shadow Mode and conditional Auto-Publish are part of current design.
3. The **Viewer Global Full Read-Only Amendment** supersedes v0.1 Viewer repository-grant and limited-finding visibility restrictions **within ASEP-managed repositories and retained/sanitized ASEP business data**.
4. Unchanged rules (local-only inference, confidentiality, freshness, no automatic approve/merge, retention, remote reconciliation) remain binding.
5. A later technical decision (e.g., TSE-01 Spring Boot, TSE-03 PostgreSQL direction) must not be retroactively attributed to an earlier ADR whose approval predated it.
6. Any unresolved ambiguity or missing original is recorded, not resolved by assumption. A newly authored consolidation must receive explicit Owner approval before it is promoted as canonical.

## 4. Historical conflicts needing rebaseline

- REQ-002 / AC-F03: manual-only MVP versus revision-aware GitLab polling.
- REQ-003, REQ-006, PUB-INV-01/02, PUB-008, SEC-08: manual exact approval versus conditional Owner-enabled automated policy with independent gates.
- REQ-008/010, SEC-02, AC-F02/F11/F13: old Viewer scoped grants versus Global Full Read-Only business reads. Mutations remain forbidden.
- PUB-001/009, AC-F05/F06/F08/F12: one current summary, Shadow behavior, manual/auto publication and valid zero-publishable-finding summary.
- OPS-002: bounded safe retries still required; prior blanket no-automatic-publication language superseded **only** when CR-001 policy gates authorize Auto-Publish.
- Wording ambiguity: 'three selected inline findings per publication' is the explicit CR-001 wording; some older summaries said 'per review'. Repeated-publication semantics require clarification, not invention.
- Identifier caution: M00-01 **SEC-09** (GitLab Quick Action protection) is not M00-02 **SEC-009** (restricted network egress).

These are documentation consistency topics, **not** new requirements or approved acceptance test cases.

## 5. Source gaps and verification

- Original full-text CR-001 Draft 2 and original standalone Viewer amendment: **SOURCE CONTENT MISSING**.
- Original section-by-section ADR-001/002 and MD/TSE comparisons: partially recovered, **not verified as verbatim original**.
- Original Project Control live status, GitLab runtime and token permissions, AI/GPU performance, Keycloak behavior, test runs and M01 implementation: **NOT VERIFIED**.
- Detailed execution-history/Human Evaluation/telemetry TTL and numerical AI quality thresholds: **DEFERRED**.
- V-01..V-14 are all **OPEN / NOT VERIFIED**.

## 6. Review/adoption gate (proposed workflow)

1. Verify file provenance and historical-source preservation.
2. Review effective requirements, ADR/module ownership and conflicts.
3. Obtain explicit Owner acceptance of the **new consolidated repository documents** and label remaining gaps.
4. Review `git status`, staged diff and absence of sensitive local data.
5. Commit only after approval, then submit M00-04 execution evidence to Project Control. Do not silently mark milestone complete.

This index is not proof that any of these steps have been completed on the user's Ubuntu repository.
