# ASEP Documentation Index and Authority Rules

**Prepared for:** M00-04 Repository Foundation & Documentation Adoption  
**Prepared:** 2026-10-18  
**Status of this index:** OWNER ACCEPTED - REPOSITORY DOCUMENTATION BASELINE (2026-10-18); provenance and verification limitations remain binding.
**Important:** Status assigned to a *source decision* is different from approval of a newly written *repository document*.

## 1. Document types and provenance

| Type | Meaning | Handling |
|---|---|---|
| Historical approved source | Original or text-extracted approved content from M00-01/M00-02 | Preserve content; do not backdate amendments or rewrite it silently |
| Approved decision; recovered text | Decision was approved; original long-form record may be unavailable | Keep provenance and explicit missing-original note |
| Owner-accepted consolidation | New repository representation assembled from historical/recovered sources | **OWNER ACCEPTED on 2026-10-18**; not equivalent to an original historical approved artifact |
| Historical roadmap snapshot | Earlier project plan or status as of a recorded handoff | Not live Project Control; do not infer ticket status changes |
| Deferred / not verified | Unresolved technology, risk, PoC, or implementation claim | Remains open until separately approved/tested |

## 2. Documentation register

| Path | Contents | Source/authority status | Repository-document status |
|---|---|---|---|
| [requirements/v0.1/PROJECT_VISION.md](requirements/v0.1/PROJECT_VISION.md) | M00-01 Project Vision v0.1 | Owner approved 2026-10-15; **historical** | Reproduced from Project Library text extraction; raw-byte identity not verified |
| [requirements/v0.1/M01_MVP_SCOPE.md](requirements/v0.1/M01_MVP_SCOPE.md) | REQ/PUB/EVAL and legacy acceptance criteria | Owner approved 2026-10-15; **partly superseded** | Reproduced from Project Library text extraction; raw-byte identity not verified |
| [requirements/M01_CURRENT_REQUIREMENTS.md](requirements/M01_CURRENT_REQUIREMENTS.md) | CR-001, Viewer amendment, effective requirement mapping | Underlying decisions approved 2026-10-17 | **OWNER-ACCEPTED CONSOLIDATION** (2026-10-18); original CR-001/amendment full text missing |
| [discovery/TECHNICAL_DISCOVERY_REPORT.md](discovery/TECHNICAL_DISCOVERY_REPORT.md) | M00-02 technical constraints, RET/OPS/SEC-009 | Owner-approved report 2026-10-16 | Copied from user-provided complete report; still historical wrt later amendments |
| [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) | System design and module boundaries | Final architecture design approved 2026-10-18 | **OWNER-ACCEPTED CONSOLIDATION** (2026-10-18) |
| [architecture/adr/ADR-001.md](architecture/adr/ADR-001.md) | Modular monolith, separate workers | ADR-001 decision approved 2026-10-16 | Owner-accepted repository decision record 2026-10-18; original not independently verified |
| [architecture/adr/ADR-002.md](architecture/adr/ADR-002.md) | Durable workflow and publication boundaries | ADR-002 decisions approved 2026-10-17 | Owner-accepted repository copy of reconstructed ADR-002 2026-10-18; original not independently verified |
| [governance/DECISIONS.md](governance/DECISIONS.md) | ADRs, MD-01..10, TSE-01..06C, deferrals | Underlying individual decisions approved to specified scopes | **OWNER-ACCEPTED REGISTER** (2026-10-18); individual source provenance retained |
| [governance/IMPLEMENTATION_ROADMAP.md](governance/IMPLEMENTATION_ROADMAP.md) | M00..M05 and reported ticket status | Roadmap v0.1 approved; original PROPOSED draft exists | **OWNER-ACCEPTED ROADMAP SNAPSHOT** (2026-10-18), not live status |
| [verification/TRACEABILITY_AND_RISKS.md](verification/TRACEABILITY_AND_RISKS.md) | Requirement mapping, acceptance/rebaseline and V-01..14 | Planning baseline approved 2026-10-18 | **OWNER-ACCEPTED CONSOLIDATION** (2026-10-18); no test results |
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

## 6. Documentation adoption and acceptance workflow

1. Verify file provenance and historical-source preservation.
2. Review effective requirements, ADR/module ownership and conflicts.
3. Owner accepted the **new consolidated repository documents** on 2026-10-18, with remaining gaps retained as disclosed below.
4. Review `git status`, staged diff and absence of sensitive local data.
5. Commit only after approval, then submit M00-04 execution evidence to Project Control. Do not silently mark milestone complete.

This index is not proof that any of these steps have been completed on the user's Ubuntu repository.

## 7. Owner acceptance record - M00-04 repository documentation

- **Date:** 2026-10-18 (ASEP M00-04 conversation).
- **Decision:** Owner **ACCEPTED** the existing consolidated documentation as ASEP's repository documentation baseline with the caveats below. The response was "Oke ổn rùi đó" to an explicit request to approve the consolidated documents under those caveats.
- **Accepted repository artifacts:** this Index, `requirements/M01_CURRENT_REQUIREMENTS.md`, `architecture/ARCHITECTURE.md`, `architecture/adr/ADR-001.md`, `architecture/adr/ADR-002.md`, `governance/DECISIONS.md`, `governance/IMPLEMENTATION_ROADMAP.md` (historical snapshot only), and `verification/TRACEABILITY_AND_RISKS.md`. Existing M00-01/M00-02 historical sources and recovered evidence remain preserved.
- **Provenance limits:** Missing original full-text CR-001 Draft 2, Viewer Amendment and some MD/TSE/ADR records are **still missing**. Owner acceptance applies to the newly prepared repository representations; it does **not** authenticate missing originals or change their historical approval dates.
- **Consistency backlog:** Legacy acceptance tests remain due for rebaseline; CR-001 uses **max three selected inline findings per publication**, while repeated-publication semantics require clarification.
- **Verification limits:** V-01..V-14, runtime integration/PoCs, numeric AI quality gates and deferred implementation choices remain **OPEN / NOT VERIFIED**. Auto-Publish remains default-off; no automatic MR approval/merge.
- **Governance limits:** This acceptance is **not** M00-04 completion approval and does not change Project Control ticket or milestone status. Git diff/security review and an additional documentation-acceptance commit are separate steps.
