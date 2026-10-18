# ASEP M01 - Requirements Traceability and Risk/Verification Backlog

> **Repository-document status: DRAFT - PENDING OWNER REVIEW.**  
> **Underlying planning baseline:** Requirements Traceability & Risk/Verification Backlog v0.1 accepted 2026-10-18 for architectural planning.  
> **Source:** `docs/sources/recovered/06_Traceability_Risk_Verification_v0.1_RECOVERED.md`, historical M01 scope, recovered requirements amendments, ADRs and Testing & Observability notes.  
> **Runtime/evidence status:** No tests, API calls, model benchmarks or infrastructure validations were performed to create this document. **All V-01..V-14 remain OPEN / NOT VERIFIED.**

## 1. Traceability rules

- The authoritative historical requirements are preserved in `requirements/v0.1/`. CR-001 v0.2 and Viewer Global Full Read-Only replace **only conflicting** v0.1 rules.
- Module/verification mappings below inherit an approved M00-03 *planning direction*, but this repository text is a new consolidation and does not create a new approval.
- An **architecture approval** means an approach was accepted. It does **not** establish an actual implementation, passing tests, runtime permissions or a safe Auto-Publish capability.
- Verification IDs V-01..V-14 identify open obligations. They are not bug-fix completion claims or code test result IDs.
- Read `docs/INDEX.md` for precedence, missing originals and supersession caveats.

## 2. Functional requirements to owner/verification

| Requirement | Owning module(s) / layer(s) | Evidence required (future) |
|---|---|---|
| **REQ-001** | Next.js UI; Identity & Access | Owner/maintainer can complete the intended authorized workflow end to end |
| **REQ-002 + CR-001** | MR Detection; Review; coordination | API polling, revision fingerprint, durable checkpoint/restart/pagination/duplicate-admission tests |
| **REQ-003 + CR-001** | Review preview; Publication; UI | Manual exact approval plus default-off Owner-enabled Auto policy; unauthorized path blocked |
| **REQ-004** | Review; Local Inference Adapter | Reproducible logic, security, reliability and regression evaluation cases |
| **REQ-005** | Review; context/grounding validator | Schema, evidence, revision, file/line and incomplete-coverage correctness |
| **REQ-006 + CR-001** | Publication; UI | Manual subset selection; qualified automated selection max three inline findings per publication |
| **REQ-007** | Detection; Review; Publication | New/unverifiable MR revision blocks stale publication; TOCTOU residual accurately documented |
| **REQ-008 + Viewer amendment** | Keycloak; Identity & Access | Owner-managed provisioning, deactivation, no public sign-up and backend role/command controls |
| **REQ-009** | Local Inference Port; runtime security | Local-only inference and egress/credential/prompt-injection tests |
| **REQ-010 + Viewer amendment** | Identity & Access; authorized Query Composition | Global retained/sanitized ASEP-managed reads incl. unpublished findings/Human Evaluation; no Viewer mutations or secrets |

## 3. Publication and evaluation requirements

| Requirement | Owning module | Verification obligation |
|---|---|---|
| **PUB-001** | Publication; Ledger | One current summary per MR; updates/retries and valid zero-publishable-finding summary |
| **PUB-002** | Publication; authorized Query Composition | Summary accurately reflects actual outcomes, reviewed revision, count/severity, partial/unknown status and protected link |
| **PUB-003** | Publication; GitLab write adapter | Inline findings use valid diff position for current revision |
| **PUB-004** | Publication | Invalid/unanchorable inline finding never silently becomes generic note |
| **PUB-005** | Publication; Ledger | Idempotency/reconciliation across ambiguous responses and per-write partial results |
| **PUB-006** | Publication | Prior inline discussions remain untouched after re-review |
| **PUB-007** | Controlled comment rendering | Source/model text cannot invoke unintended GitLab Quick Actions |
| **PUB-008** | Publication; IAM policy | Manual exact payload/approval, current Owner-enabled Auto-Publish policy and independent gates |
| **PUB-009** | Publication; Ledger | Shadow runs/new revisions alone do not modify current summary; qualified publication may update |
| **EVAL-001** | Evaluation; independent labeled data | Benchmarks and model-quality gates with independent ground truth; Owner approves numerical thresholds before M01/Auto activation |

**Superseded acceptance criteria:** Historical `AC-F02`, `AC-F03`, `AC-F05`, `AC-F06`, `AC-F08`, `AC-F11`, `AC-F12`, `AC-F13` cannot be used unchanged. Their behavior must be remapped to Viewer Full Read-Only, polling/Shadow/Auto-Publish, and zero-findings summary. Re-examine AC-F10 with automatic detection, and maintain compatible AC-F07/F09 safety. Proposed new test cases do not acquire approval merely by appearing here.

## 4. Retention, operational and security constraints

| ID | Requirement / policy | Evidence owner and future checks |
|---|---|---|
| **RET-001** | Unapproved findings expire after 30 days meaningful Owner inactivity | Review retention semantics and expiry integration test |
| **RET-002** | Approved but unpublished findings expire after 30 days meaningful Owner inactivity | Review approval/expiry/invalidated approval test |
| **RET-003** | Published finding evidence/content expire after 90 days successful publication | Publication content lifecycle/expiry test |
| **RET-004** | Sanitized audit events expire after 90 days | Audit record sanitization and TTL test |
| **RET-005** | Minimal Publication/Reconciliation Ledger retained 365 days, then deleted | Ledger data minimization, reconciliation and expiration tests |
| **RET-006** | Sanitized operational logs retained 30 days | Log leakage check and storage TTL verification |
| **RET-007** | Delete expired sensitive finding content/evidence; minimal audit marker has separate TTL | Deletion, caches, backups/replicas and marker consistency test |
| **RET-008** | Only meaningful Owner actions reset inactivity, not Viewer reads, refresh, polling | Clock/refresh/poll negative tests |
| **OPS-001** | Cleanup temporary source/diff/job data after completion/failure/cancel and crash leftovers | Review job lifecycle and scavenger tests |
| **OPS-002** | Bounded safe retry and revision revalidation; qualified CR-001 Auto-Publish exception | Workflow retry/recovery and current policy tests |
| **OPS-003** | At most one active Review and one Publication operation per MR at a time | PostgreSQL concurrency/admission tests |
| **OPS-004** | Removing integration deletes local credential, cancels jobs/pending data, retains published history by TTL | Integration lifecycle/credential deletion tests |
| **SEC-009 (M00-02)** | Restrict network egress to required GitLab/local services; no external AI inference or telemetry by default | Runtime network/egress and credential isolation tests |

**Identifier clarification:** M00-01 `SEC-09` is comment Quick Action injection protection, not the M00-02 egress policy `SEC-009`.

## 5. V-01..V-14 - all OPEN / NOT VERIFIED

| ID | Open risk/verification | Required proof; no current pass claim |
|---|---|---|
| **V-01** | GitLab token permissions and authenticated real APIs | Authorized test project; read/write/inline behavior under real GitLab.com Free token and roles |
| **V-02** | Poll/revision detection and dedupe consistency | Pagination/overlap/rate limits, checkpoint recovery, repeated poll and restart tests |
| **V-03** | Stale GitLab publication race | Concurrent revision changes and revalidation; document residual external TOCTOU limits |
| **V-04** | Duplicate remote writes, unknown outcomes, reconciliation | Simulated lost responses; real ledger/remote matching, one-summary invariants |
| **V-05** | Worker claims/fencing, crashes, Outbox recovery | PostgreSQL atomic claims/transactions, lease expiry/stale Worker rejection, duplicate handoff |
| **V-06** | Default-off Shadow/Auto-Publish safety policy and gates | No writes in Shadow, disabled-by-default, max three, zero findings summary, no retroactive publishing, disable policy race |
| **V-07** | Local model and review quality | Independent labeled precision/recall, false positives, grounding; Owner-approved thresholds before activation |
| **V-08** | Incomplete/truncated MR diff/context | Explicit scope/coverage/provenance reporting; refuse misleading fully-reviewed claims |
| **V-09** | Keycloak/OIDC/BFF/JWT authorization and revocation | Keycloak session/login, PKCE, token validation, Owner/Viewer read-vs-mutation and deactivation tests |
| **V-10** | Credential secrecy, egress and prompt injection | Redaction, model prompts, external traffic, adversarial source/comments and process boundary |
| **V-11** | TTLs, cleanup, cache prohibition and integration removal | Retention clock, crash scavenging, sensitive-data cleanup and credentials cancellation tests |
| **V-12** | GPU VRAM/model latency/context feasibility | Actual local inference benchmark, GPU runtime/OOM, context quality and latency |
| **V-13** | Deployment/process and credential isolation | Compose/process role resource separation, network policy, restart/safe recovery tests |
| **V-14** | Backup/restore and ledger expiration implications | Accepted recovery design and retention/reconciliation after ledger TTL |

Do **not** close V-03/V-04/V-06/V-07 to enable Auto-Publish without independent evidence. A design might reduce but cannot promise to eliminate external GitLab TOCTOU races.

## 6. M00-02 historical risk register (not replaced by V IDs)

M00-02 recorded independent historical risk entries R-01..R-10. These IDs were **not renumbered into** V-01..V-14 and remain open until evidence closes them:

| M00-02 ID | Historical issue |
|---|---|
| R-01 | GitLab onboarding/PAT least privilege untested |
| R-02 | Stale publication race |
| R-03 | Incomplete/collapsed/oversized MR diffs |
| R-04 | GPU in Docker unverified |
| R-05 | AI quality/latency/context unbenchmarked |
| R-06 | Crash cleanup and job recovery untested |
| R-07 | Duplicate reconciliation after ledger TTL unverified |
| R-08 | Exact inactivity/TTL semantics untested |
| R-09 | Automated backup/restore deferred |
| R-10 | Prompt injection/authorization/confidentiality tests pending |

See historical report for full descriptions. This consolidated traceability does not silently mark these resolved.

## 7. Testing/observability proof model

**Keep three evidence types separate:**

1. **Domain history and Publication Ledger**: authoritative business record for durable execution and external write outcomes.
2. **Sanitized telemetry**: operational logs/metrics/traces to diagnose behavior and failure, never the authoritative recovery state.
3. **Independent AI Evaluation**: labeled ground truth and model/prompt regression evidence, distinct from a human approval/acceptance rate.

| Proposed design proof | Test or artifact required |
|---|---|
| **AT-01** | Spring Modulith declared module boundaries verified; Review cannot import privileged Publication internals |
| **AT-02** | PostgreSQL/Testcontainers atomic claim, lease/fencing and outbox correctness |
| **AT-03** | Duplicate/stale/partial/ambiguous GitLab publication verified via controlled adapters and real integration where safe |
| **AT-04** | Keycloak/BFF/API authentication, Viewer rights and mutation denials |
| **AT-05** | Finding grounding, incomplete context and prompt-injection security tests |
| **AT-06** | Process-level Worker crash/restart and recovery behavior |
| **AT-07** | Redaction, egress, expiry, cleanup and sensitive-data leak checks |
| **AT-08** | Complete review lifecycle reconstructed from durable state/ledger, not log-only assumptions |

AT-01..AT-08 are **proposed architectural evidence gates** documented in the recovered Testing & Observability source, not passing tests or approved M01 acceptance results. Tools such as JUnit, Mockito, Testcontainers, Playwright, Actuator/Micrometer and OTel-compatible instrumentation are directions/candidates; exact versions and CI cadence are deferred.

## 8. Gate hierarchy and downstream allocation

| Gate | What can legitimately be concluded |
|---|---|
| **M00-03 architectural design gate** | Logical module ownership, traceability, decision provenance and risks identified; OPEN technical risks may remain |
| **M00-04 adoption gate** | Repository documents accurately reflect approved sources and unresolved gaps after Owner review, diff review and secure Git commit |
| **M01 functional acceptance gate** | Working GitLab/Keycloak/local AI flow with actual recovery, authorization, TTL/security and publication verification evidence |
| **Auto-Publish activation gate** | Current Owner-enabled repository policy + independent AI quality/publication safety tests, runtime evidence and explicitly accepted residual risk |

These are different milestones. Passing a review of Markdown files cannot satisfy runtime/quality gates.

Potential downstream owners (planning, **not roadmap changes**): M01 implementation verifies GitLab/AI/Keycloak/Worker/security behavior; future separately-approved infrastructure work addresses backup maturity and optional K3s/Jenkins/Rancher learning.

## 9. Open documentation consistency items

- Original CR-001 Draft 2 / standalone Viewer amendment texts missing; reconstruction needs explicit Owner approval if promoted to repository source of truth.
- Rebaseline historical requirements/acceptance tests, notably `AC-F02/F03/F05/F06/F08/F11/F12/F13`, legacy Viewer restrictions and unconditional manual-publication wording.
- Formal meaning of 'max three selected inline findings **per publication**' versus earlier 'per review' summary needs clarification before test design for repeated publications.
- Detailed execution history/Human Evaluation TTL and observability storage choices deferred; do not retain indefinitely by accident.
- Other numeric model/safety thresholds, exact Outbox/scheduler choices and GitLab race semantics remain open.
- This document's new consolidation text awaits Owner approval; reported approved underlying planning baseline is not a passing CI pipeline.

## 10. Sources

- [Recovered Traceability & Verification baseline](../sources/recovered/06_Traceability_Risk_Verification_v0.1_RECOVERED.md).
- [Recovered Testing & Observability](../sources/recovered/05_Testing_Observability_v0.1_RECOVERED.md).
- [Current Requirements consolidation](../requirements/M01_CURRENT_REQUIREMENTS.md).
- [Original M00-02 Technical Discovery](../discovery/TECHNICAL_DISCOVERY_REPORT.md).
- [System Architecture](../architecture/ARCHITECTURE.md) and [Decision Register](../governance/DECISIONS.md).
