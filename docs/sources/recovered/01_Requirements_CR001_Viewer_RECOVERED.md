# 01 — Requirements Baseline v0.2: CR-001 & Viewer Amendment
**Status:** APPROVED DECISION / RECOVERED SUMMARY — **ORIGINAL FULL-TEXT SOURCES MISSING**
**Provenance:** M00-04 handoff (`Pasted text(7).txt`) and M00-03 Execution Summary (`Pasted text(6).txt`). Requirements Baseline v0.1 originals (`PROJECT_VISION.md`, `M01_MVP_SCOPE.md`) are separately available.

## 1.1 CR-001 — exact wording found in the M00-04 handoff
> CR-001 — Requirements Baseline v0.2
>
> Bao gồm:
> - Automatic MR Review thông qua GitLab API polling.
> - Revision-aware detection và processing.
> - Shadow Mode không ghi lên GitLab.
> - Conditional Auto-Publish, mặc định disabled.
> - Auto-Publish chỉ được kích hoạt theo Owner policy và các quality/safety gates.
> - Tối đa ba selected inline findings trong một publication.
> - Một current ASEP summary cho mỗi MR.
> - Summary hợp lệ cho review không có publishable findings.
> - Web UI review details.
> - Human Evaluation.
> - Không tự động approve hoặc merge MR.

This block is a verbatim extract of the **handoff summary**, NOT the complete text of CR-001 Draft 2.

Additional decision-level boundaries confirmed by the M00-03 summary:
- GitLab API polling includes durable admission/checkpoint/recovery and revision-aware processing.
- Publication policy is repository-specific, Owner-controlled and default-off.
- Independent evaluation and publication safety gates remain binding.
- Historical Shadow Reviews are not retroactively published merely because Auto-Publish is turned on.
- No AI-generated arbitrary code execution or automatic merge in M01.

## 1.2 Viewer Global Full Read-Only Amendment — exact wording from M00-04 handoff
> Viewer được phép đọc business data và execution history còn được lưu trong ASEP-managed repositories, kể cả unpublished findings và Human Evaluation.
>
> Viewer không được thực hiện mutations, workflow commands, configuration changes, feedback actions hoặc truy cập raw secrets.
>
> Amendment này thay thế repository-scoped Viewer restriction của Requirements Baseline v0.1.

This is an exact extract of the **handoff summary**, not the standalone Viewer amendment full text.

## 1.3 Original v0.1 → supersession mapping (RECOVERED TRACEABILITY, not verbatim CR text)

| ID | Earlier v0.1 baseline | Current amendment effect |
|---|---|---|
| REQ-002 | Manual/on-demand Review; webhook auto-review deferred | Automatic polling/revision-aware detection and Review as part of current CR-001 scope |
| REQ-003 | Preview and explicit Owner approval before each publication | Manual approval remains; conditional Owner-policy Auto-Publish path added |
| REQ-006 | Owner selects approved findings | Manual selection remains; conditional automated selection, max 3 inline findings |
| REQ-008 | Owner-created users; Viewer repo-grants | Owner provisioning remains, repository-scoped Viewer restriction superseded |
| REQ-010 | Viewer sees shareable summary and approved/published findings within granted repositories | Global Full Read-Only across ASEP-managed repositories, including retained unpublished findings and Human Evaluation |
| PUB-001 | Single current summary/MR, updated after approved publish | One current summary retained; valid zero-publishable-finding summary allowed under current publication policy |
| PUB-008 | Exact rendered manual Owner approval for specific MR snapshot | Manual path preserved; Owner-enabled Auto-Publish follows its specific current policy/gates |
| PUB-009 | Summary represents last approved published state | Shadow-only Review doesn't update summary; authorized Auto-Publish may update |
| OPS-002 | Bounded safe retries; no automatic publication in original scope | Safety rules persist; Auto-Publish only where expressly authorized by CR-001 |
| EVAL-001 | Baseline-first AI quality evaluation | Independent quality assessment and later Owner-approved thresholds prerequisite to Auto-Publish |

Important: Do not rewrite unrelated PUB-002..007 or RET-001..008 unless amendments expressly supersede them. Authority precedence: v0.2 + amendments override only conflicting parts of v0.1.

## 1.4 Approval provenance (recorded, not verbatim signed documents)
- 2026-10-15: Owner approved `PROJECT_VISION.md` and `M01_MVP_SCOPE.md` as Requirements Baseline v0.1.
- 2026-10-17: CR-001 Draft 2 / Requirements Baseline v0.2 accepted as APPROVED (original long-form approval message not retrieved in this file corpus).
- 2026-10-17: Viewer Global Full Read-Only Amendment APPROVED; separate formal amendment ID not verified.
- 2026-10-18: Owner explicitly approved Final System Architecture Baseline v0.1 retaining Requirements v0.2 and amendments. The exact Owner final signoff is present in M00-03 conversation/Execution Summary.

## 1.5 Missing original artifacts
- CR-001 Draft 2 full-text with every requirement, scope clause, and supersession rule.
- Viewer Full Read-Only standalone amendment full-text with its original ID (if any).
- Original 2026-10-17 Owner approval messages/verbatim context.

**Do NOT label this recovered document as original approved CR-001 or use it as the sole source of truth for M00-04 requirements adoption.**
