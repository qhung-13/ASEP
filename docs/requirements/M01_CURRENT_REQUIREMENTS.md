# ASEP M01 - Current Requirements Consolidation (v0.2 + Amendments)

> **Repository document status: OWNER-ACCEPTED CONSOLIDATION (2026-10-18; M00-04).**
> **Underlying decision status:** Requirements v0.1 approved 2026-10-15; CR-001 v0.2 and Viewer Global Full Read-Only Amendment approved according to 2026-10-17 conversation/handoff; final architecture reaffirmed them on 2026-10-18.  
> **Provenance:** Historical v0.1 full texts plus `docs/sources/recovered/01_Requirements_CR001_Viewer_RECOVERED.md` and M00-03 Final Architecture Review. Full original CR-001 Draft 2 and standalone Viewer amendment **not recovered**.  
> This file consolidates evidenced decisions and was accepted as a repository document on 2026-10-18; it **does not claim to reproduce the missing original** or introduce new implementation decisions.

## 1. M01 outcome and boundary

M01 is a self-hosted AI Merge Request Reviewer for GitLab.com, operated by an individual repository maintainer using a Web UI. ASEP detects monitored MR revisions, obtains only relevant permitted context, performs **local-only** evidence-focused AI review, preserves review and execution history according to retention policies, and controls comment publication through a separate, auditable process.

M01 is **not** an AI code editor, automated merge/approval agent, general CI replacement, Jira investigator or multi-tenant SaaS product. Future M02-M05 workflows remain separate milestones.

## 2. Approval and precedence

| Decision source | Approved date / status | Effect |
|---|---|---|
| Requirements Baseline v0.1 | 2026-10-15; approved historical text | Original REQ-001..010, PUB-001..009, EVAL-001 and security/reliability expectations |
| CR-001 / Requirements v0.2 | 2026-10-17; approved decision evidence, original full text missing | Automatic polling/revision review; Shadow Mode; Owner-policy conditional Auto-Publish; zero-findings summary; full review UI and evaluation |
| Viewer Global Full Read-Only Amendment | 2026-10-17; approved decision evidence, original full text missing | Supersedes Viewer repository-scoped and limited-findings rules |
| Final System Architecture Baseline v0.1 | 2026-10-18; approved **design level** | Confirms above requirements; does not prove implementation |

Only conflicting portions of v0.1 are superseded. The unchanged security, freshness, reconciliation, minimal-persistence, retention and no-auto-merge requirements still apply. The original v0.1 documents in `requirements/v0.1/` remain unchanged historical records.

## 3. Effective product requirements (REQ-001..010)

| ID | Effective decision-based statement | Provenance / change |
|---|---|---|
| REQ-001 | Primary operator is the Individual Developer / Repository Maintainer (Owner). | v0.1 retained |
| REQ-002 | ASEP automatically discovers and reviews GitLab MR revisions through API polling with revision-aware durable admission. Manual review/re-review may remain a user path. | CR-001 supersedes manual-only initial-MVP restriction |
| REQ-003 | Manual publication requires Owner review and approval of exact rendered content and MR snapshot. Separately, conditional Auto-Publish is permitted only when the Owner has enabled its repository policy and independent quality/safety gates pass. | CR-001 extends manual approval rather than eliminating its safeguards |
| REQ-004 | Focus review on logic/behavior errors, security issues supported by evidence, reliability/error handling, and regressions. | v0.1 retained |
| REQ-005 | Findings contain category, severity, explanation, technical evidence and actionable remediation, with a location where determinable. Incomplete context/coverage is disclosed. | v0.1 retained; Review validates provenance |
| REQ-006 | Manual path supports selecting findings for publication. Conditional Auto-Publish applies gated automatic selection with **at most three selected inline findings per publication**. | CR-001 amendment; exact cross-publication interpretation flagged below |
| REQ-007 | Block newly published feedback if the reviewed MR revision is stale or freshness cannot be verified. Revalidation reduces but does not eliminate external TOCTOU races. | v0.1 retained; ADR-002 recovery caveat |
| REQ-008 | Owner controls account creation, deactivation and access; there is no public registration. Viewer is global **read-only for retained/sanitized data in ASEP-managed repositories**, no longer subject to per-repository Viewer grants. | Viewer Amendment overrides old grants, not GitLab access rules |
| REQ-009 | Model inference over source code occurs within self-hosted ASEP, without external AI providers. Model never receives secrets or privileged GitLab write capabilities. | v0.1 retained; SEC-009 remains applicable |
| REQ-010 | Viewer can read ASEP-managed retained business data and execution history, including unpublished findings and Human Evaluation. Viewer cannot mutate state, send workflow commands, change configuration, provide feedback or read raw secrets. | Viewer Amendment overrides old visibility restrictions |

### Role boundaries

| Capability | Owner | Viewer |
|---|---|---|
| Log in and view retained ASEP-managed review/execution data | Yes, authorized | Yes, Global Full Read-Only within defined scope |
| View unpublished findings and Human Evaluation | Yes | Yes, if retained and sanitized |
| Start/restart/cancel review and change workflow state | Authorized Owner operations | No |
| Configure GitLab integrations and enable/disable Auto-Publish | Owner only | No |
| Review/select/approve manual publication | Owner only | No |
| Post evaluation feedback and create/disable accounts | Owner only, according to policy | No |
| View raw credentials/secrets | Protected operations only; never unconditional disclosure | No |

Global Viewer access is **not** access to all external GitLab repositories. GitLab comments continue to be governed by GitLab permissions, independently of ASEP dashboard permissions. Backend authorization must enforce the boundary on list/detail/search and command APIs, not merely the UI.

## 4. Current review and publication modes

### Automatic detection and review

- Poll tracked GitLab repositories/MRs; identify revisions, maintain durable checkpoints and deduplicate admission.
- Record review revision and relevant source/diff provenance; explicitly flag truncated/missing context.
- Run local inference; validate output, evidence and schema; **zero findings is a valid review result**, never proof that no bug exists.
- Make complete review details, safe execution timeline and Human Evaluation available in the Web UI subject to retention.
- Crash/retry and new MR revisions cannot imply publication permission.

### Shadow Mode

- Review and evaluation can run normally.
- **No GitLab writes are made** due to Shadow review.
- Shadow-only reviews do not change the current MR summary.
- Enabling Auto-Publish does **not** retroactively publish historical Shadow reviews.

### Manual publication

- Owner selects eligible findings, examines the exact rendered payload and revision, and explicitly approves publication.
- Publication checks current revision, allowed policy, authorization, valid inline anchors and comment rendering at execution time.
- A zero-publishable-finding summary is allowed only by the current publication policy and with applicable manual approval, not merely because a review returned no findings.

### Conditional Auto-Publish

- **Disabled by default.** Owner must enable an applicable repository policy.
- Independent model-quality and publication-safety gates are prerequisites, not implied by a completed review or a unit-test pass.
- Current policy, revision, valid anchors, scope, and content safety must be checked at the publication boundary.
- Select **at most three inline findings per publication** under approved policy; a valid zero-publishable-finding review may produce/update the current summary where policy authorizes.
- A policy toggle is not a command to publish historical Shadow reviews.
- No automatic MR approval, merge, code edits or arbitrary model-triggered commands.

**Deferred:** Numeric model-quality thresholds and the exact policy/gate evaluation implementation must be independently tested and later approved. There is **no evidence Auto-Publish may currently be enabled safely**.

## 5. Publication policy (PUB-001..009)

| ID | Current application of approved policy |
|---|---|
| PUB-001 | Exactly one **current ASEP summary** per MR (create or update with reconciliation); valid publication can include a zero-publishable-finding summary. Historical inline discussions remain. |
| PUB-002 | Summary reflects reviewed revision, actual confirmed publication counts/severity, partial/unknown outcomes and protected dashboard link; do not leak unapproved private findings. |
| PUB-003 | Each inline finding requires a verified valid **current diff position**. |
| PUB-004 | No automatic fallback to a generic MR comment for an unanchorable finding; retain it in the dashboard. |
| PUB-005 | Use a durable ledger; reconcile remote state after ambiguous timeouts or partial writes; no blind replay or external exactly-once claim. |
| PUB-006 | Do not automatically resolve/delete/rewrite previous GitLab inline discussions after a new review. |
| PUB-007 | Render comments through controlled templates and guard against unintended GitLab Quick Actions from untrusted text. |
| PUB-008 | Manual mode: exact Owner approval of rendered payload and reviewed revision. Auto mode: explicit current Owner-enabled policy and independent safety/quality gates. |
| PUB-009 | Shadow/new AI review alone must not rewrite the current summary; qualified manual/Auto publication may update it based on **actual** remote publication state. |

**External write boundary:** Check freshness as close as practical to each write; stop further writes upon discovering an MR revision change. A network failure after GitLab accepted a comment yields UNKNOWN, not a safe unconditional retry. There is no atomic transaction spanning ASEP DB and GitLab. Avoid duplicate summary creation. Persist minimal per-write outcome and reconciliation records under RET-005.

## 6. Evaluation (EVAL-001)

Build a baseline-first, independently labeled evaluation dataset. Measure issue-level precision/recall, false-positive rate, grounded evidence/location accuracy, reviewed input coverage and latency. Include clean cases, known bugs, incomplete-context and adversarial prompt-injection cases. Human acceptance alone is **not** independent correctness evidence. Freeze methodology, benchmark actual local runtime/model, and seek Owner approval of numeric gates before M01 acceptance or Auto-Publish activation.

M00-01 suggested 32 example cases as a **PROPOSED** dataset size; it is not an approved minimum or quality threshold.

## 7. Security, reliability, retention

- M00-01: preserve SEC-01..10 where non-conflicting; **SEC-02** repository-scoped Viewer restriction and **SEC-08** unconditional manual approval language require test rebaseline. The restriction on Viewer **mutations** persists.
- Note identifier distinction: **SEC-09** in M00-01 is GitLab comment Quick Action security; **SEC-009** in M00-02 limits outbound network egress.
- M00-02 **SEC-009**: egress only to required GitLab integrations and local services; no external model inference or telemetry by default.
- **OPS-001..004**: cleanup/scavenge temporary context, safe bounded retries and revision revalidation, at most one active Review plus one active Publication per MR, and integration removal cancellation/credential cleanup. OPS-002 blanket no-auto-publish wording is limited by the later CR-001 authorization path.
- **RET-001..008**: unapproved and approved-unpublished findings expire after 30 days of meaningful Owner inactivity; published findings/evidence after 90 days; sanitized audit 90 days; minimal ledger 365 days; sanitized logs 30 days; expired local content is deleted, with minimal marker under its own TTL; Viewer reads/polling do not reset inactivity.
- Temporary raw source/diff/model prompts are not durable reusable caches by default. No cloud inference or unauthorized telemetry.
- Exact backup/restore implementation, Human Evaluation/detailed-history TTL and telemetry backend/TTL remain deferred.

For the authoritative historical RET/OPS text see [Technical Discovery Report](../discovery/TECHNICAL_DISCOVERY_REPORT.md).

## 8. Superseded acceptance tests - documentation reconciliation backlog

Do **not** execute obsolete v0.1 acceptance criteria as if they remain the effective M01 specification. The source recovery pack explicitly flagged these: `AC-F02`, `AC-F03`, `AC-F05`, `AC-F06`, `AC-F08`, `AC-F11`, `AC-F12`, `AC-F13`. The following mappings are **proposals for later test design, not approved replacement IDs**.

| Historical criterion | Necessary rebaseline |
|---|---|
| AC-F02, AC-F11, AC-F13 | Viewer can read all authorized retained ASEP-managed business data (including unpublished), while all Viewer mutations/secrets remain denied |
| AC-F03, AC-F10 | Polling-driven revision detection/review plus manual re-review path, durable dedupe |
| AC-F05, AC-F06, AC-F08 | Separate successful Review vs allowed Publication; manual exact approval and conditional gated Auto mode |
| AC-F12 | Valid zero-publishable-finding review may publish a summary **only under authorized current publication path**, not always no GitLab write |
| AC-F07, AC-F09 | Keep freshness protection and single summary; additionally validate re-review, Shadow and auto policy interplay |
| SEC-02, SEC-08, PUB-INV-01/02 | Reinterpret legacy unconditional Viewer grant/manual-only wording according to later amendments |

## 9. Explicit source and decision gaps

- Full original CR-001 Draft 2 and standalone Viewer Amendment: **SOURCE CONTENT MISSING**. No invented amendment ID.
- Exact semantics of max three inline findings across multiple publication attempts for the same Review: **NEEDS CLARIFICATION**; CR-001 wording is **per publication**.
- Numerical quality/activation thresholds, job/schema/identity/runtime mechanisms and actual GitLab API behaviors: **DEFERRED / NOT VERIFIED**.
- This repository consolidation was **OWNER ACCEPTED on 2026-10-18** under the documented source gaps; it must not be tagged as the historical signed-off original.

## 10. Sources

- [M00-01 M01 MVP Scope v0.1](v0.1/M01_MVP_SCOPE.md) and [Project Vision v0.1](v0.1/PROJECT_VISION.md) - historical approved texts.
- [M00-04 CR-001/Viewer recovered evidence](../sources/recovered/01_Requirements_CR001_Viewer_RECOVERED.md) - reconstruction, not original CR full text.
- [M00-03 Architecture recovered notes](../sources/recovered/02_System_Architecture_Detailed_Design_RECOVERED.md).
- [Architecture document](../architecture/ARCHITECTURE.md) and [Traceability](../verification/TRACEABILITY_AND_RISKS.md) - Owner-accepted repository consolidations, not original archived records.
