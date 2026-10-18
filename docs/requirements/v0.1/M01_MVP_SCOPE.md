# ASEP — M01 AI Merge Request Reviewer: MVP Scope & Acceptance Baseline v0.1

> **Status:** APPROVED — Requirements Baseline v0.1 (explicit Owner sign-off on 2026-10-15); PROPOSED items remain non-binding  
> **Parent ticket:** M00-01 — Project Vision & MVP Scope  
> **Target milestone:** M01 — AI Merge Request Reviewer  
> **Prepared:** 2026-10-15  
> **Evidence status:** Specification only. No repository, implementation, tests or deployment verified.  
> **Important:** [CONFIRMED] = explicitly approved by Owner; [PROPOSED] = awaits decision; [NOT VERIFIED] = requires later technical investigation. On 2026-10-15 Owner approved the Publication, Security and Reliability policy packages, plus the Risk Register and downstream handoff classification; complete M00-01 requirements baseline received explicit Owner sign-off on 2026-10-15; PROPOSED items are still non-binding.

## 1. Goal / MVP Definition

Create a **self-hosted, local-AI-assisted GitLab MR review workflow** that a single Owner operates primarily via Web UI. ASEP reads permitted diff and relevant code context, produces **risk-focused, evidence-backed findings**, lets the Owner preview and choose findings, and publishes **only explicitly approved feedback** to the MR after validating freshness. Viewer accounts may read reviews within repositories the Owner explicitly authorizes.

This is a controlled review assistant, not an AI merger, autonomous developer or general-purpose code editor.

## 2. Confirmed Decisions / Traceability

| ID | Decision | Authority status |
|---|---|---|
| REQ-001 | Individual Developer / Repository Maintainer is the primary user | CONFIRMED |
| REQ-002 | Initial MVP review starts manually/on-demand; auto-review later after validation within M01 | CONFIRMED |
| REQ-003 | Preview findings and require explicit approval before GitLab publication | CONFIRMED |
| REQ-004 | Review coverage: logic bugs, security, reliability/error handling, potential regressions | CONFIRMED |
| REQ-005 | Evidence-based actionable findings: category, severity, explanation, evidence, fix, location if available | CONFIRMED |
| REQ-006 | Selective finding approval; do not publish unapproved findings | CONFIRMED |
| REQ-007 | Block stale review publication; require new review when MR version changes | CONFIRMED |
| REQ-008 | Owner-managed accounts and Viewer read permission scoped by repository/project | CONFIRMED |
| REQ-009 | Local-only AI inference; no code sent to external AI inference provider | CONFIRMED |
| REQ-010 | Viewer sees shareable summaries and Owner-approved/published findings only in granted repositories; Owner sees all findings | CONFIRMED |
| PUB-001 | One current summary comment per MR, updated on Owner-approved new publication; keep old inline comments and complete ASEP history | CONFIRMED |
| EVAL-001 | Baseline-first AI evaluation; threshold set after local benchmark and before M01 acceptance | CONFIRMED |
| UI direction | Web UI is the primary user interface | CONFIRMED |

**Key distinction:** The above decisions authorize product behavior, **not** a particular framework, architecture, authentication technology, data schema, GitLab token, model, or queue.

## 3. User Roles and Authorization

| Capability | Owner | Viewer |
|---|---|---|
| Sign in | Yes | Yes |
| View reviews of authorized repositories | Yes | Yes |
| Start manual review/re-review | Yes | No |
| Select/approve/publish findings | Yes | No |
| Manage GitLab integration / repository grants | Yes | No |
| Create/disable accounts | Yes | No |

**[CONFIRMED]** Owner creates accounts; Viewers cannot act outside their read-only permissions and repository grants.

**[CONFIRMED SECURITY POLICY; REQ-010]** No public sign-up; newly created Viewer gets no repository access until expressly granted (default deny); server enforces resource-level authorization for all review/list/detail APIs; deactivation revokes protected access; accounts are individual rather than shared. Viewer sees **shareable review summaries and Owner-approved/published findings only** for granted repositories. An approved finding awaiting publication remains visible with its accurate state; a pending/rejected finding is not accessible to Viewer through UI or API. Owner sees all findings and review drafts. GitLab itself uses GitLab access permissions for comments already posted there.

## 4. Initial MVP In Scope

1. Basic authentication, Owner-created accounts and repository-scoped Viewer access.
2. Minimal Web UI: sign-in, MR selection/input, review status, findings/details, selective approval, publish result and review history; minimal account/repository access management for Owner.
3. Authorized GitLab MR retrieval: validate project/MR, obtain diff/version and only necessary relevant code context.
4. Manual trigger/re-review; no automatic webhook start in initial MVP.
5. Local AI analysis for four risk categories, structured findings validation, ability to return zero findings.
6. Owner preview and explicit approval of selected findings and exact feedback text/snapshot before GitLab write.
7. Stale detection and refusal to publish stale/unverifiable review.
8. GitLab summary plus **approved and diff-anchorable** inline feedback, subject to GitLab Publication Policy below.
9. Clear review/publication outcome, bounded failures and sufficient audit/history for debugging.
10. Reproducible AI quality evaluation with agreed methodology and later quality gate.

### Explicitly Out of Scope for Initial MVP

- Jira ticket investigation, implementation, testing/generation of code fixes, draft MR creation, autonomous merge/approval.
- AI execution of arbitrary shell commands or repository modifications.
- GitLab event/webhook-triggered auto-review (later M01 iteration, *not* initial MVP acceptance criterion).
- Complex multi-agent orchestration, organization/multi-tenant SaaS, public sign-up, fine-grained arbitrary RBAC.
- Full analytics/operating dashboard, general lint/style-only feedback, cloud AI inference.

## 5. Core Workflow (Happy Path)

1. **Authenticate / authorize.** Owner signs in and chooses an allowlisted GitLab repository/MR.
2. **Manual start.** Owner explicitly starts review in Web UI. ASEP records run identity and target MR/revision.
3. **Context retrieval.** ASEP requests GitLab diff and relevant source context. If partial, mark the limits; do not claim full coverage.
4. **Local AI review.** AI analyzes code changes; output is validated as structured findings with technical evidence or empty results.
5. **Preview.** Owner sees full result in ASEP. **No GitLab comment exists solely because review completed.**
6. **Select & approve.** Owner picks findings and explicitly approves the previewed, exact publication payload and reviewed revision.
7. **Freshness check.** ASEP verifies the current MR version/required state and permissions. If changed/unverifiable, block new publication.
8. **Publish approved feedback.** Only approved/anchorable findings are attempted as inline discussions; ASEP reconciles responses and records per-finding outcomes. GitLab summary is created/updated to reflect what was **actually published** and links to a protected ASEP report, subject to the same approval.
9. **History.** ASEP stores review-run snapshots, approval information, individual publication states, and GitLab note/discussion references.
10. **Manual re-review.** After another push, Owner may request a new review. Previous inline discussions remain; summary is changed **only after the new review's approved publication**, not merely after a push or another AI run.

**[CONFIRMED PUBLICATION POLICY]** If there are zero findings or Owner selects none, ASEP makes **no** GitLab writes and preserves the result in the dashboard. A separate approved “clean review summary” could be designed later; it is not assumed.

## 6. Structured Finding Contract (Product-Level, Not Schema)

Mandatory content: category, severity, meaningful explanation, source-based evidence, actionable suggested fix. Code location is provided **when determinable**. Each finding is linked to a specific review-run snapshot. Unsupported guesses should be excluded or explicitly labeled uncertain rather than asserted as bugs. No arbitrary minimum number of findings.

**[PROPOSED]** Findings must pass format validation before preview. Invalid output yields a controlled failure or limited retry; it must not be published by default. Severity taxonomy and JSON schema are reserved for design/implementation.

## 7. GitLab Publication Policy

### 7.1 Confirmed invariants

- **PUB-INV-01 [CONFIRMED, REQ-003]:** Never create or update GitLab feedback without explicit Owner approval of what will be published.
- **PUB-INV-02 [CONFIRMED, REQ-006]:** Never publish an unselected/unapproved finding.
- **PUB-INV-03 [CONFIRMED, REQ-007]:** Never newly publish a stale review or one whose freshness cannot be verified.
- **PUB-INV-04 [CONFIRMED, PUB-001]:** Maintain **one current ASEP summary note per MR**; update it on Owner-approved subsequent publications. Preserve old inline discussions and full ASEP review history.

### 7.2 Publication behavior approved by Owner (2026-10-15)

| ID | Approved behavior | Status |
|---|---|---|
| PUB-002 | Summary shows reviewed revision, count/severity of **actually published** approved findings, publication status and authenticated dashboard link; excludes rejected/unapproved findings | CONFIRMED |
| PUB-003 | Publish a selected finding as an inline discussion only if correct current diff position is verified | CONFIRMED |
| PUB-004 | Unanchorable finding stays visible in dashboard; **no automatic general-comment fallback**. Owner must revise selection; separate explicit fallback can be deferred | CONFIRMED |
| PUB-005 | Publication of unchanged approved payload is idempotent to the extent implementable: reconcile GitLab state before retry; record individual write outcomes; no blind retry after ambiguous response | CONFIRMED |
| PUB-006 | Do not auto-resolve, remove, or rewrite previous inline discussions after re-review | CONFIRMED |
| PUB-007 | All comment bodies are rendered from controlled templates and checked/sanitized so AI/source-controlled text cannot invoke unintended GitLab quick actions (e.g. `/merge`, `/approve`) | CONFIRMED |
| PUB-008 | Approving findings approves their exact rendered GitLab output and MR snapshot; modifying content after approval requires renewed Owner confirmation | CONFIRMED |
| PUB-009 | The summary is the **last Owner-approved published state**, not automatically the newest AI run or newest MR commit. Reviewed revision remains visible to prevent misinterpretation | CONFIRMED |

### 7.3 Failure / concurrency boundaries

- Check freshness before each external write when practical. If MR changes during a multi-comment publication, stop subsequent writes; record comments already acknowledged. **GitLab does not provide a shared atomic transaction** covering freshness check and multiple comment writes. Zero-race or external exactly-once guarantees must not be asserted.
- If inline comments succeed but the summary update fails, publication is **partial**, not “all successful.” Record an actionable reconciliation state.
- The system must not create a second ASEP summary simply because its previous write timed out. Check GitLab notes using stored IDs/identification before creating another summary.
- If publish results are ambiguous, reconcile with GitLab instead of submitting the same write blindly.
- Restrict outbound GitLab publication to comments. **Never** approve/merge, resolve discussions or execute arbitrary MR changes as a side effect of AI output.
- Remember that GitLab comment visibility is governed by GitLab permissions and may not match ASEP Viewer permissions. Publish only explicitly approved, minimally necessary information.

**Official GitLab references:**
- MR Notes create/update: https://docs.gitlab.com/api/notes/
- Diff discussions and SHA positions: https://docs.gitlab.com/api/discussions/
- MR diff references: https://docs.gitlab.com/api/merge_requests/
- Quick actions in comments: https://docs.gitlab.com/user/project/quick_actions/

## 8. Functional Acceptance Criteria (Behavioral)

| ID | Given / When / Then result | Linked requirements |
|---|---|---|
| AC-F01 | Given valid Owner account, when Owner signs in, then protected UI/actions are available according to role | REQ-008 |
| AC-F02 | Given Viewer without grant to repo B, when opening its review URL or API, then deny access and do not reveal repo content | REQ-008 |
| AC-F03 | Given authorized test MR, when Owner starts review manually, then run is created; a webhook alone cannot initiate an initial-MVP review | REQ-002 |
| AC-F04 | Given valid MR, when context is gathered, then diff/revision is recorded, relevant context collected, and incomplete context explicitly marked | REQ-004/005 |
| AC-F05 | Given usable local AI, when review completes, then findings conform to agreed fields (or zero findings) and **no GitLab write** has occurred | REQ-003/004/005/009 |
| AC-F06 | Given multiple findings, when Owner selects a subset and approves exact payload, then nonselected findings are excluded from all publication | REQ-003/006 |
| AC-F07 | Given MR revision changed or version check unavailable, when Owner publishes old run, then **no new feedback is posted** and re-review is required | REQ-007 |
| AC-F08 | Given authorized, fresh approved content, when publication succeeds, then only selected inline findings and an approved summary appear; ASEP records GitLab references | REQ-003/006, PUB-001 |
| AC-F09 | Given prior summary exists, when new approved run is published, then the same summary note is updated rather than creating a new one; prior inline discussions remain | PUB-001 |
| AC-F10 | Given new commit and prior run, when Owner triggers re-review, then a distinct run is recorded; old approvals do not authorize publishing the new content | REQ-002/007 |
| AC-F11 | Given review data, when permitted Owner/Viewer opens dashboard, then Owner sees complete results but Viewer sees only shareable summary + approved/published findings, with correct publication status, in a granted repository | REQ-008/010 |
| AC-F12 | Given zero findings or no selected findings, when publication is requested, then no GitLab comment is created | REQ-003/006; approved publication default |
| AC-F13 | Given Viewer attempts to fetch rejected or pending approval findings via list/detail/search API, when Owner has not approved those findings, then backend denies disclosure even if Viewer has access to the repository | REQ-010 |

## 9. Failure Scenarios / Test Expectations

| ID | Fault / negative case | Expected behavior |
|---|---|---|
| FAIL-01 | Invalid MR URL or ID | Validation error, no AI run |
| FAIL-02 | MR missing or access forbidden | Controlled denial; do not leak private project metadata |
| FAIL-03 | GitLab API timeout/5xx/429 | Bound retries with backoff or explicit failure; no unbounded loop |
| FAIL-04 | Diff too large or context omitted | Explicit partial coverage and limits; no unsupported “fully reviewed” claim |
| FAIL-05 | Local AI unavailable or timed out | Review fails/times out transparently; no invented findings |
| FAIL-06 | AI returns malformed findings | Validate/retry within policy; never silently publish malformed output |
| FAIL-07 | AI produces zero findings | Valid result; do not claim code definitely bug-free |
| FAIL-08 | Owner cancels in-progress review | Controlled stop/cancel state; no implied approval/publication |
| FAIL-09 | Viewer calls Owner-only endpoint | Deny; verify at backend, not just hide UI button |
| FAIL-10 | Reviewer account revoked/deactivated | Re-check authorization; deny subsequent protected actions |
| FAIL-11 | MR advanced after review | Stale publication blocked and old review preserved in history |
| FAIL-12 | Some GitLab inline writes succeed and later ones fail | Track per-comment results, report partial state and reconcile |
| FAIL-13 | GitLab accepted write but response was lost | Do not blindly retry; reconcile remote state |
| FAIL-14 | Diff position cannot be anchored | Do not post at an invented line; keep finding in dashboard |
| FAIL-15 | Malicious code/comment contains GitLab quick action or prompt-injection instruction | Treat as untrusted data; no unauthorized GitLab actions or secret disclosure |
| FAIL-16 | MR changes during publication | Stop further writes when change discovered, record partial external effects; do not assert atomic rollback |
| FAIL-17 | GitLab summary update fails after inline writes | Preserve accurate per-finding state; do not mark complete publication |

## 10. Non-Functional Acceptance Criteria

### 10.1 Security (policy APPROVED; technical mechanisms deferred to architecture/security design)

| ID | Requirement | Test / evidence |
|---|---|---|
| SEC-01 | Auth required; Owner-only actions denied to Viewer | Integration/API access-control tests |
| SEC-02 | Default deny and repository-scoped authorization on every read/write | Cross-repository negative tests |
| SEC-03 | Owner-created users, account revocation and separate credentials | Auth and revoked-session scenarios |
| SEC-04 | Keep ASEP-managed secrets out of prompts, public API responses and application logs | Redaction/leak tests |
| SEC-05 | No external AI inference traffic carrying code/context | Configuration inspection and egress testing |
| SEC-06 | Treat repo/MR/tool content as untrusted instructions | Prompt-injection adversarial tests |
| SEC-07 | AI cannot invoke arbitrary code execution, code edits, MR approval or merge | Capability and integration-boundary tests |
| SEC-08 | Publication requires fresh revision + exact Owner approval | Negative end-to-end/integration tests |
| SEC-09 | GitLab comment quick actions cannot be injected via AI/source content | Malicious comment-body tests |
| SEC-10 | Material actions are auditable without logging unnecessary raw code/secrets | Audit/logging tests |

**[NOT VERIFIED]** Exact GitLab token identity and permissions. On GitLab.com Free, project access tokens are not generally available without Premium/Ultimate; using a user credential may entail broader API powers. Do not assume a read-only token can post notes. Study token scopes, project membership and compensating controls during security design. Source: https://docs.gitlab.com/user/project/settings/project_access_tokens/ and https://docs.gitlab.com/security/tokens/access_token_scopes/ .

### 10.2 Reliability & usability (policy APPROVED; implementation mechanisms deferred)

| ID | Requirement | Test / evidence |
|---|---|---|
| REL-01 | Configurable run/request timeouts | Timeout injection and clear user-facing states |
| REL-02 | Bounded retries, rate-limit handling, no blind write retries | Stubbed GitLab failures, ambiguous responses |
| REL-03 | Cancellation possible for running review | Cancellation test, no subsequent publication |
| REL-04 | Partial publication accurately represented | Inject failure on Nth write |
| REL-05 | Review traceability across MR revisions | Versioned run and publication history tests |
| REL-06 | Controlled context size/concurrency suitable for local hardware | Stress test after hardware verification |
| REL-07 | Actionable error UI without sensitive leakage | UI/API error test |

**Initial performance budget [APPROVED AS CONFIGURABLE PROVISIONAL DEFAULTS, NOT AN SLO]:** warn after ~5 minutes, cap a run near ~10 minutes, request timeout ~15–30 seconds. These are not fixed contractual performance targets; reassess using actual Ubuntu PC CPU/GPU/RAM and local model. Record p50, p95, max review latency; do not claim quality/performance already met.

## 11. Evaluation Strategy and Measurable Outcomes

**[CONFIRMED, EVAL-001]** Baseline-first: define and freeze evaluation dataset/method first, benchmark the local AI model, then set and approve quantitative quality thresholds **before accepting M01**.

**[PROPOSED] Curated dataset v0.1:** 32 cases (6 logic, 6 security, 6 reliability, 6 regression, 8 clean), with MR-like changes, sufficient context, independent ground truth and expected locations/fixes where applicable. This case count is not yet a binding requirement.

**[PROPOSED] Metrics:** issue-level Precision, Recall, false-alarm rate on clean MRs, evidence accuracy, location accuracy, actionability, p50/p95 latency. Do not calculate undefined denominators as zero. Deduplicate issue-level matches. Prefer human-verified evidence/tests as ground truth over self-grading by the same AI.

**External datasets to investigate, not automatically integrate:** SWE-PRBench (MR review examples) and BugsJS (real JavaScript bugs). Confirm license, fit, evaluation leakage and local-offline execution before adopting.

### Future M01 Acceptance Gates (proposed until M01 execution planning)

1. **End-to-end functionality:** demonstrate at least one complete manual review + approved feedback publication on an actual **test** GitLab MR, verified by human inspection.
2. **Mandatory safety tests:** 100% pass of defined approval, repository authorization, stale-publication and local-only egress tests; failure-path tests also meet their specified outcomes. Passing tests does not prove zero unknown vulnerabilities.
3. **Publication consistency:** deterministic retry/ambiguous-response/partial-publication scenarios are correctly reconciled with no duplicate comments in those tested scenarios.
4. **AI evaluation:** benchmark is reproducible; measured precision/recall/evidence/latency meets Owner-approved thresholds **to be set after baseline runs**.
5. **Demonstrable operation:** review history, failure states and evidence available for a portfolio demo.

### M00-01 Acceptance (current ticket)

M00-01 requires an approved requirements specification, not implementation or benchmark results. Its gates are: Project Vision and users, MVP boundaries, workflow/failures/security, testable requirements, constraints/assumptions, and final Owner sign-off. The project/control conversation is responsible for roadmap governance; this approved requirements document does not update it.

## 12. Assumptions, Risks, Open Questions & Downstream Handoff

**Register status: APPROVED AS TRACKING/HANDOFF BASELINE (2026-10-15).** Risks remain OPEN, assumptions remain NOT VERIFIED, and proposed priority labels are not measured probabilities. Approval of this register does not mean risks are resolved or accepted as safe. The complete M00-01 requirements baseline was approved by Owner on 2026-10-15; unresolved technical questions remain open.

### 12.1 Assumptions and verification dependencies

| ID | Assumption / unknown | Current state | When to verify / handoff |
|---|---|---|---|
| ASM-01 | Actual GitLab account/project, API token privileges, ability to read MRs and create/update notes and discussions | NOT VERIFIED | Before GitLab adapter/integration testing; M00 Security + M01 integration investigation |
| ASM-02 | Ubuntu PC CPU/GPU/RAM, model context capacity and review throughput | NOT VERIFIED | Before selecting model/runtime and defining final time budgets; M00 Architecture + M01 benchmark |
| ASM-03 | ASEP repository location, branch, existing architecture/authority docs | NOT VERIFIED | Before editing repository or adopting these draft files; M00 repository investigation |
| ASM-04 | Local AI quality on risk-focused review cases and reproducible evaluation dataset | NOT VERIFIED | M01 evaluation; quality thresholds approved before M01 acceptance |
| ASM-05 | Jira integration plan/permissions and Jira Free limitations | NOT VERIFIED / FUTURE | Later M02 investigation; not a blocker to initial M01 MVP |

### 12.2 Risk register and mitigations

| ID | Risk / potential impact | Priority | Expected control / later owner |
|---|---|---|---|
| RISK-01 | False positives, hallucinated evidence, poor model quality undermine reviewer trust | HIGH | Ground truth, measured precision/recall/evidence accuracy, Owner approval; M01 evaluation |
| RISK-02 | Prompt injection in MR/source context attempts unauthorized model behavior | HIGH | Untrusted-content boundary and adversarial security tests; M00 Security / M01 |
| RISK-03 | Source snippets or credentials leak through prompts, logs, API, dashboard, or GitLab publication | HIGH | Local-only inference, data minimization, redaction, access controls, retention policy; M00 Security |
| RISK-04 | MR diff too large, omitted/unchorable lines, insufficient context yield incomplete findings | MEDIUM | Explicit review coverage limits, no invented anchors; M01 retrieval/review UI |
| RISK-05 | MR changes concurrently with GitLab publication, leaving externally visible partial writes | HIGH | Revision re-checks, stop on detected change, record external effects; M01 publication design |
| RISK-06 | Lost GitLab API acknowledgement yields unknown comment status or duplicates on retry | HIGH | Publication reconciliation by external identifiers; do not retry ambiguous writes blindly; M01 publication design |
| RISK-07 | GitLab.com Free/token restrictions lead to wider write access than desired | HIGH | Verify actual account/tier/scopes and compensating controls; M00 Security |
| RISK-08 | Incorrect repository-level authorization exposes unpublished source evidence to Viewers | HIGH | Backend default deny and negative access tests; M00 Security / M01 |
| RISK-09 | Local model exhaustion, long context or timeout degrades reliability | MEDIUM | Configurable limits, measurement, cancellation and bounded concurrency; M00 Architecture / M01 |

Priority levels are **initial triage ratings in the approved register**, not measured probabilities. Owner approval confirms tracking and downstream assignment, not that any mitigation was implemented or tested.

### 12.3 Resolved decisions vs. remaining open questions

| ID | Question / decision | Status and destination |
|---|---|---|
| OQ-01 | Viewer may see pending/rejected findings? | **RESOLVED — REQ-010:** No; only shareable summary + approved/published findings for granted repositories |
| OQ-02 | Auto-fallback to general comment for unanchorable finding? | **RESOLVED — PUB-004:** No automatic fallback in initial MVP |
| OQ-03 | Summary content after partial publication? | **RESOLVED — PUB-002/005:** Report verified published outcomes only; record and display partial state |
| OQ-04 | Exact retention duration, deletion/export semantics for review data and cached source? | **OPEN — M00 Security:** Define before storing real sensitive repository context |
| OQ-05 | Final numeric latency/SLO and AI quality thresholds? | **DEFERRED — Benchmark:** Confirm after hardware/model evaluation, before M01 acceptance |
| OQ-06 | Actual GitLab token/identity choice and handling of broad write permission? | **OPEN TECHNICAL INVESTIGATION — M00 Security / M01 integration** |
| OQ-07 | Precise API/data model/job orchestration, diff anchor and race handling mechanisms? | **OPEN DESIGN — Later M00/M01 technical tickets**, not a M00-01 decision |

### 12.4 Requirements boundary and handoff

These unresolved **technical verification/design** questions do not assert that the current deployment is ready. M00-01 may define and approve a testable scope while assigning investigations to later tickets; M01 cannot be accepted without validating dependencies and satisfying its quality/safety gates.

**Deferred to later M00 architecture/security tickets:** concrete technologies, login/session protocol, schema, job execution architecture, cancellation/idempotency technique, token choice/storage, GitLab adapter, network egress controls, prompt isolation, model selection, resource sizing, telemetry and data retention implementation.

## 13. Final Review Checklist / Approval Record

- [x] Preserve confirmed REQ-001..010, PUB-001..009 and EVAL-001; Web UI is primary.
- [x] Keep manual MVP distinct from future GitLab webhook trigger.
- [x] Distinguish selective approval from GitLab MR approval/merge.
- [x] Describe summary update vs historical inline discussions.
- [x] Include negative and partial failure tests.
- [x] Identify local AI security and free-tier credential constraints.
- [x] **Owner approved Publication, Security and Reliability policy packages (2026-10-15).**
- [x] **Owner confirmed Viewer visibility boundary (REQ-010).**
- [x] **Owner approved the Assumptions, Risks & Open Questions Register as OPEN/NOT VERIFIED tracking with downstream handoffs (2026-10-15).**
- [x] **Owner approved the full M00-01 requirements baseline, including both documents, on 2026-10-15.**

**Document status: APPROVED REQUIREMENTS BASELINE v0.1 — full Owner sign-off 2026-10-15; PROPOSED items remain non-binding.**  
**M00-01 status: Eligible for proposed COMPLETED in Execution Summary; Project Control status unchanged.**

## 14. Scope of Final Owner Approval

Owner final sign-off, explicitly given on 2026-10-15 in the M00-01 execution conversation, approves the **M00-01 Project Vision and the M01 MVP requirements baseline** as the planning reference for downstream M00/M01 work. It does **not** approve implementation architecture, make specific candidate technologies mandatory, fix numeric AI quality thresholds, guarantee GitLab API permissions, verify local hardware, or close OPEN risks.

Items still labeled **PROPOSED** (e.g. exact 32-case curated dataset size and optional benchmark details) remain non-binding planning suggestions unless separately approved; final baseline sign-off does not silently convert them into commitments. The fixed performance values remain provisional configuration starting points and not verified SLOs.

**Approval evidence:** Owner explicitly approved `PROJECT_VISION.md` and `M01_MVP_SCOPE.md` as Requirements Baseline v0.1 on 2026-10-15. No repository updates or Project Control status changes are implied.