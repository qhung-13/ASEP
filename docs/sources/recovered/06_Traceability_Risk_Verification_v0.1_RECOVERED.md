# 06 — Requirements Traceability & Risk/Verification Backlog v0.1
**Owner APPROVED:** 2026-10-18, architectural planning baseline.
**Provenance:** Recovered from M00-03 authored review plus M00-03 Execution Summary; not evidence of running tests.
**Precedence:** Requirements Baseline v0.2/CR-001 and Viewer Global Full Read-Only supersede conflicting v0.1 requirements.

## 1. Requirement → module → verification matrix

| Requirement | Responsible modules / capabilities | Verification obligation |
|---|---|---|
| REQ-001 | Next.js UI, Identity & Access | Maintainer/Owner end-to-end workflow |
| REQ-002 + CR-001 | MR Detection, Review, coordination | Poll/revision fingerprint/checkpoint/restart/duplicate tests |
| REQ-003 | Review preview, Publication, UI | Manual approval + Owner policy gates for Auto-Publish |
| REQ-004 | Review, Local Inference Adapter | Logic/security/reliability/regression evaluation fixtures |
| REQ-005 | Review, grounding/context validator | Schema, source evidence, location/provenance accuracy |
| REQ-006 | Publication | Manual subset selection and max-three gated auto selection |
| REQ-007 | Detection/Review/Publication | Changed/unverifiable revision prevents stale publication |
| REQ-008 | Keycloak, Identity & Access | Owner provisioning, deactivation, authorization |
| REQ-009 | Inference Port, security/runtime boundary | Local-only egress tests, secrets and prompt-injection testing |
| REQ-010 + Viewer Amendment | Identity & Access, authorized Query Composition | Viewer global retained/sanitized business reads, no mutation or secrets |

## 2. Publication policy traceability

| ID | Responsible module | Verification |
|---|---|---|
| PUB-001 | Publication + Ledger | One current summary per MR; retry/review updates; zero publishable findings |
| PUB-002 | Publication + authorized read composition | Summary reflects actual outcomes/count/severity/revision/link, partial state |
| PUB-003 | Publication, GitLab write adapter | Valid current GitLab diff inline positions |
| PUB-004 | Publication | No silent unanchorable-to-general-comment fallback |
| PUB-005 | Publication + Ledger | Duplicate prevention, ambiguous-response reconciliation, per-write outcomes |
| PUB-006 | Publication | Preserve prior inline discussions after re-review |
| PUB-007 | Controlled comment renderer | GitLab Quick Action sanitization |
| PUB-008 | Publication | Exact manual payload approval; current Owner-enabled Auto-Publish policy/gates |
| PUB-009 | Publication + Ledger | Shadow/new revision alone does not rewrite current summary |

**Superseded cases from v0.1:** AC-F02/03/05/06/08/11/12/13 and old Viewer negative tests require new acceptance mappings; do not run them as if they still describe current behavior.

## 3. EVAL / RET / OPS / SEC mapping
**EVAL-001:** independent labeled ground truth, local model benchmark, accuracy/grounding, quality thresholds to be Owner-approved before M01 acceptance/Auto-Publish. Owned by Evaluation with read-only Review outputs, not implicit publication authority.

| ID | Constraint | Verification/owner |
|---|---|---|
| RET-001/002 | Unpublished findings 30-day meaningful Owner inactivity | Review lifecycle + expiry tests |
| RET-003 | Published content/evidence 90 days | Publication lifecycle + expiry tests |
| RET-004 | Sanitized audit 90 days | Audit storage TTL |
| RET-005 | Minimal Ledger 365 days | Publication Ledger TTL and data minimization |
| RET-006 | Sanitized operational logs 30 days | Telemetry retention |
| RET-007 | Erase expired sensitive content/evidence; minimal marker by its own TTL | Domain cleanup & cache tests |
| RET-008 | Only meaningful Owner actions reset inactivity | Viewer refresh/poll cannot extend TTL |
| OPS-001 | Temporary cleanup incl. crash leftovers | Lifecycle/Review crash scavenger tests |
| OPS-002 | Bounded retries with MR revision/policy checks | Durable workflow failures |
| OPS-003 | One active Review and one Publication operation/MR | PostgreSQL concurrency/admission |
| OPS-004 | Integration removal cleanup, cancellation, credential deletion | Integration + domain cascade tests |
| SEC-009 | Restricted GitLab/local egress; no external inference/telemetry by default | Deployment network tests |

Source-code, raw diffs and prompts cannot be stored across runs without a distinct Owner-approved cache policy. No automatic merge.

## 4. Risk/Verification Backlog (all OPEN/NOT VERIFIED)
| ID | Risk / unanswered verification | Proposed proof |
|---|---|---|
| V-01 | GitLab token permissions and real APIs | Authorized test repo read/write; verify GitLab.com Free behavior |
| V-02 | Polling/revision detection consistency | Restart, pagination, overlap, checkpoint & dedupe tests |
| V-03 | Stale publication race | Concurrent pushes/revalidation + document residual external TOCTOU risk |
| V-04 | Duplicate GitLab writes & reconciliation | Lost responses, ledger matching and remote searches |
| V-05 | Worker crash/fencing/Outbox | PostgreSQL transactions, lease expiry, stale commit rejection |
| V-06 | Auto-Publish safety/policy | Default-off, Shadow, max 3, zero findings, disable and no retroactive publish |
| V-07 | AI quality thresholds | Independent gold dataset/precision/recall/grounding |
| V-08 | Truncated diff / missing context | Coverage/provenance and incomplete-review markings |
| V-09 | Keycloak/BFF/JWT/authorization | OIDC, issuer/audience, Owner/Viewer, revocation tests |
| V-10 | Secrets, egress, prompt injection | Runtime isolation, adversarial inputs, telemetry redaction |
| V-11 | Retention/cleanup correctness | TTL, crash cleanup, integration removal, optional cache absence |
| V-12 | GPU/VRAM/local inference | Model benchmark, OOM/latency/capacity |
| V-13 | Deployment and process isolation/recovery | Compose network/process-credential and restart tests |
| V-14 | Backup/restore and ledger expiration | Recovery impact analysis, future accepted strategy |

**High consequence:** V-03/04/06/07 cannot be treated as closed or bypassed merely to enable Auto-Publish. External GitLab write races may require explicit residual risk acceptance; no zero-race claim.

## 5. Gate levels
**M00-03 architecture gate:** Module ownership, decision provenance, architectural requirement coverage and V-01..14 assigned. OPEN technical risks may remain if unambiguous and tracked.
**M01 functional acceptance:** Working GitLab/local inference integration, authorization, durable recovery, retention and approved acceptance tests actually verified.
**Auto-Publish activation:** Owner-enabled repository policy and independent model quality & publication safety gates with real evidence; not inferred from unit tests alone.

## 6. Downstream allocation (not roadmap changes)
M00-03: architectural consistency and risk ownership.
M01 implementation: real GitLab/API/Keycloak checks, Worker recovery, local model benchmarking, publication safety/AI quality.
M05/DevOps learning: backup/restore maturation, monitoring and Kubernetes/Jenkins/Rancher as separately approved.
This allocation is a planning proposal; tickets/roadmap statuses require Project Control approval.

## 7. Approval record
2026-10-18: Owner accepted the Requirements Traceability & Risk/Verification Backlog v0.1 as an **architectural planning baseline**. Approval does not close V-01..V-14, approve implementation details, or imply test results.
