# ASEP — M00 Source Recovery Pack
**Prepared:** 2026-10-18
**Purpose:** Recover materials relevant to M00-04 Repository Foundation & Documentation Adoption.
**Important:** These recovery documents are NOT replacements for original approved source texts unless expressly identified as exact extracts. They do NOT create new approvals, amend requirements, update roadmap, or close verification risks.

## Provenance labels
- **EXACT APPROVED TEXT AVAILABLE:** Source text identified and accessible through the ASEP Project Library/conversation, not necessarily reproducible as an exported original file.
- **APPROVED DECISION / RECOVERED SUMMARY:** Approval is evidenced, but original detailed standalone document or verbatim conversation text could not be recovered. Content here is a documented reconstruction and must NOT silently become a canonical approved document.
- **SOURCE CONTENT MISSING:** Need original text from M00-03 or the Owner before canonical documentation adoption.
- **DEFERRED / NOT VERIFIED:** Does not become approved/implemented as a consequence of this recovery pack.

## Eight requested categories

| # | Area | Recovery status | Included |
|---|---|---|---|
| 1 | CR-001 Draft 2, Viewer Full Read-Only, approval provenance | APPROVED SUMMARY AVAILABLE; **full original texts missing** | `01_Requirements_CR001_Viewer_RECOVERED.md` |
| 2 | Detailed System Architecture | APPROVED Final Review available in Project Library; detailed material partly RECOVERED | `02_System_Architecture_Detailed_Design_RECOVERED.md` |
| 3 | MD-01..MD-10 | APPROVED DECISIONS; full standalone texts missing | `03_Module_Decisions_MD01_MD10_RECOVERED.md` |
| 4 | TSE-01..TSE-06C | APPROVED directions; full original comparisons missing | `04_Technology_Evaluations_TSE01_TSE06C_RECOVERED.md` |
| 5 | Testing & Observability v0.1 | Original assistant-authored design accessible in M00-03 conversation; APPROVED direction | `05_Testing_Observability_v0.1_RECOVERED.md` |
| 6 | Traceability & Risk Backlog v0.1 | Original assistant-authored design accessible in M00-03 conversation; APPROVED planning baseline | `06_Traceability_Risk_Verification_v0.1_RECOVERED.md` |
| 7 | ADR Register | Approved register structure; ADR-001/002 approval verified, verbatim ADRs missing | `07_ADR_Architecture_Decision_Register_RECOVERED.md` |
| 8 | Roadmap v0.1, Project Control | Original Project Control v0.1 PROPOSED document accessible in Library; later handoff status snapshot available | `08_Implementation_Roadmap_Project_Control_RECOVERED.md` |

## Identified original sources (available through Project files / Library)
1. `PROJECT_VISION.md` — Owner-approved Requirements Baseline v0.1, 2026-10-15.
2. `M01_MVP_SCOPE.md` — Owner-approved Requirements Baseline v0.1, 2026-10-15; portions are SUPERSEDED.
3. `Pasted markdown(20261014-152242).md` — initial Project Control/Roadmap v0.1 text **PROPOSED** on 2026-10-14 (later Roadmap v0.1 Owner approved; the attached source remains historically proposed).
4. `Pasted text(9).txt` — Final System Architecture Baseline Review (copy of assistant-authored final review), followed by Owner approval 2026-10-18.
5. `Pasted text(6).txt` — M00-03 Execution Summary, 2026-10-18.
6. `Pasted text(7).txt` — M00-04 Repository Foundation & Documentation Adoption handoff, reports M00-01/02/03 COMPLETED, M00-04 READY, and current approved baselines.
7. `Pasted text(3).txt` — M00-02 Technical Discovery approved summary, with RET/OPS/SEC policies.

**Retrieval limitation:** Underlying source texts can be read through the Project/Library file interface, but raw-file materialization of the Library entries was not authorized in this session. They are therefore referenced rather than silently reproduced as purported exact copies.

## Crucial non-claims
- No recovered document verifies a deployed ASEP system, passing tests, real GitLab API behavior, Keycloak integration, GPU performance, or secure process isolation.
- No original full-text `CR-001 Draft 2`, full Viewer Amendment, individual MD-01..10 decision records, individual TSE records, or original ADR-001/002 long-form record has been confirmed as available.
- MD and TSE approval scope is preserved, but descriptions in this pack are recovery notes, **not an exact verbatim reproduction**.
- PoCs, implementations, V-01..V-14 and deferred choices remain OPEN/NOT VERIFIED as documented.
- Do not use these files to silently overwrite authoritative v0.1 documents or mark M00-04 acceptance criteria as met.

## Suggested safe adoption workflow
Retrieve missing original decisions from the dated M00-03 conversation where available; compare with these recovered notes; resolve conflicts with the Owner; only then publish version-controlled authoritative documents with exact approval provenance and supersession links. Review Git diff and sensitive data before commit.
