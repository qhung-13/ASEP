# ASEP vNext — Evidence-Gated Jira-to-Draft-MR MVP Scope

**Prepared:** 2026-10-22
**Status:** OWNER-DIRECTED vNext REQUIREMENTS UPDATE (2026-10-22) — P-01/P-02/P-03 accepted at design level; numeric gates, Jira/CI specifics and new sensitive-data policy remain OPEN. Live-repo adoption and runtime verification NOT VERIFIED.
**Relationship to M01:** Additive Owner-directed vNext scope. Does **not** replace effective M01 Review/Publication requirements, historical M01 acceptance criteria, or their open verification duties.

## 1. Goal and outcomes

ASEP demonstrates one **bounded, local-first SDLC automation vertical slice**:

`Eligible Jira ticket → Admission → Investigation → Planning → Isolated Implementation → Independent Tests → Independent Review / Policy Gates → Authorized GitLab Draft MR → Human Review / Merge`

A successful automation outcome means a Draft MR was created from an eligible, traceable candidate revision **after independent verification and authorization gates**. It does **not** mean the ticket is accepted, CI is green, the MR is approved/merged or source code is free of bugs.

Each run exposes trustworthy status: `COMPLETED_DRAFT_MR`, `BLOCKED`, `ESCALATED`, `FAILED`, `CANCELLED`, `SUPERSEDED`, or `RECONCILIATION_REQUIRED`, with explanation/evidence. Names are proposed domain vocabulary, not database enums.

## 2. User and authorization assumptions

- **Owner:** configures repository/ticket eligibility and automation limits; may start, pause/cancel, inspect and override through **authorized** commands; human decides final merge.
- **Viewer:** retain approved M01 Global Full Read-Only access to sanitized, retained ASEP-managed M01 business data. **Do not silently extend** this permission to raw Jira descriptions, repository source, agent transcripts or unclassified vNext artifacts. Proposed vNext read-model access requires policy/data-classification review. No Viewer mutations/secrets.
- **Keycloak** remains the identity provider; **Application** remains the business-authorization authority for users and integrations.
- No unauthenticated/public sign-up or automatically trusted Jira ticket instructions.

## 3. vNext functional requirements (accepted design direction)

| ID | Requirement (proposal) | Owner / key boundary |
|---|---|---|
| VNX-REQ-01 | Admit only explicitly eligible Jira ticket/repository pairs with a durable ticket/revision identity and deduplication | Application intake + Go admission |
| VNX-REQ-02 | Record initiating policy version, source revision, actor/trigger, job identity and execution budget prior to execution | Go workflow authority |
| VNX-REQ-03 | Investigation gathers minimum necessary allowed repository/ticket context with references and incomplete-coverage disclosures | OpenHands agent under scoped read-only tools |
| VNX-REQ-04 | Planning produces actionable bounded work intent, assumptions and verifiable acceptance targets; unsupported plans block/escalate | Agent reasoning; independent gate |
| VNX-REQ-05 | Owner-configured policy permits internal stage advancement without human approval at each stage **only** after gate checks; default deny, cancellation and escalation always apply | Application policy + Go gates |
| VNX-REQ-06 | Implementation edits code exclusively inside assigned restricted sandbox and cannot access host secrets, privileged GitLab/Jira credentials or protected branches | Agent Supervisor/sandbox |
| VNX-REQ-07 | Trusted independent tests execute candidate/build code in a **separate unprivileged sandbox** using a protected controller/harness; verify sealed artifact identity, test discovery and outcomes; agent-reported success is insufficient | Independent Verifier Controller |
| VNX-REQ-08 | Internal independent code/security review and policy gates produce evidence; errors may trigger bounded rework/replan or block/escalate | Go gates + trusted verification |
| VNX-REQ-09 | Only a Trusted External Action Executor may push a permitted source branch and create a GitLab Draft MR; verify exact base/candidate/target identities, enrollment, CI side effects, safe rendered MR body and current policy | Application Code Delivery capability |
| VNX-REQ-10 | Treat **branch push** and **Draft MR creation** as separate durable, reconcilable steps; ambiguous `UNKNOWN` outcomes cannot be blindly retried; never assume remote exactly-once | Trusted Action Executor + ledger |
| VNX-REQ-11 | Human alone approves/merges; no auto-merge, auto-approve, protected-branch push or production deploy | External-action authorization boundary |
| VNX-REQ-12 | Inference is local-only initially; no external AI, telemetry, uncontrolled network egress or automatic cloud fallback | Model/egress policy (P-03) |
| VNX-REQ-13 | Record minimal durable execution/evidence metadata while minimizing raw source, prompts and agent session retention; cleanup and scavenging are required | Domain-owned lifecycle |
| VNX-REQ-14 | Retries, correction, timeouts, cost/token limits and concurrency are bounded, restart-safe and observable; stale results cannot transition workflow state | Go admission/scheduler/gates |
| VNX-REQ-15 | Dashboard reports workflow, stage, independent verification and GitLab write status accurately; `Draft MR created` and `CI passed` are separate facts | Authorized Application read model |
| VNX-REQ-16 | Integration removal/revocation blocks new writes, cancels eligible work, deletes write credentials per policy; preserve minimal unresolved operation marker for **manual** reconciliation when read capability is gone, without claiming remote rollback | Application + Go + Executor |

## 4. Policy model — P-02 scope (accepted design direction)

**Deny by default.** A valid ticket description never grants autonomous rights. The Owner-defined policy constrains:

- Repository and ticket/project eligibility; source/target branch allowlists.
- Allowed workflow stages and tool capabilities per stage.
- Local model/provider policy (local-only for initial slice).
- Wall-clock, step/token, resource and retry/replan budgets (numeric values OPEN).
- Required validation evidence (tests, revision/digest integrity, internal review, security controls).
- Permitted GitLab operations: constrained feature branch + **Draft** MR only; separate existing comment-publication policy.
- Explicit stop, revoke, cancel and escalate conditions.

**Authorization meaning:** approval of a bounded execution policy **is not** approval of an arbitrary patch, any GitLab write, publication of review findings, or a merge. All external writes are separately re-authorized when executed.

### Stage gates (design obligations; exact thresholds OPEN)

| Transition | Required condition |
|---|---|
| Admission → Investigation | Eligible ticket/repository, stable dedupe identity, active policy, capacity/budget |
| Investigation → Planning | Valid scoped evidence, source revision/provenance, acknowledged missing coverage |
| Planning → Implementation | Actionable bounded plan, allowed files/tools, policy authorizes coding |
| Implementation → Verification | Candidate artifact reference/digest, workspace isolated, no untrusted self-asserted success |
| Verification → Review | Independently executed tests meet current gate, source still compatible |
| Review → Code Delivery | Independent review/security gate passes, sealed candidate matches protected test evidence, current policy/remote heads and enrolled CI execution profile still permitted |
| Code Delivery → Draft MR recorded | Confirmed **branch head** and **Draft MR identity/head/flag** recorded from separate operations; ambiguity remains UNKNOWN/RECONCILIATION_REQUIRED |

Gates may choose `ADVANCE`, `REWORK`, `REPLAN`, `RETRY_INFRA`, `BLOCK`, or `ESCALATE`. Only Go commits a valid authoritative transition. Numeric quality and budget thresholds are OPEN until specified and approved.

## 5. In scope (initial bounded slice)

1. Eligible ticket intake/admission; precise automatic polling cadence deferred (manual initiation is a valid entry path).
2. One repository and ticket at a time is an acceptable initial operational constraint; arbitrary throughput targets are not required.
3. Evidence-backed investigation and planning; scoped code changes in an isolated workspace.
4. Locally executed reasoning through an ASEP-owned Python/OpenHands adapter; no cloud dependency.
5. Trusted test execution, internal independent review and limited correction/escalation.
6. Constrained feature-branch push and Draft MR creation with operation ledger/reconciliation.
7. Observable outcome and human-controlled final merge.

## 6. Explicitly out of scope

- Autonomous merge/approval, protected-branch writes, production deployments.
- Claude/cloud inference, cloud fallback, external telemetry by default.
- Uncontrolled ingestion from all Jira projects or untrusted public webhooks.
- Generalized multi-agent team hierarchy, organization multi-tenancy, parallel swarm or agent-to-agent negotiation framework.
- New business features in the existing M01 reviewer, broad GitLab comment Auto-Publish changes or retroactive publication.
- CI auto-remediation beyond bounded initial requirements until explicitly scheduled/approved; **CI status must be tracked honestly** when present.
- New Redis/RabbitMQ/Kafka, MariaDB or Kubernetes dependencies without an evidenced need.

## 7. Proposed acceptance and verification cases (all NOT VERIFIED)

| ID | Given / When / Then |
|---|---|
| VNX-AC-01 | Given ticket outside Owner-allowed scope, when admission is attempted, then no workflow/agent task with authority is started |
| VNX-AC-02 | Given the same stable ticket/revision repeatedly, when admission retries, then at most one valid active logical workflow is admitted per policy |
| VNX-AC-03 | Given ticket/repository prompt injection, when agent reads context, then no external privileged command is granted or executed |
| VNX-AC-04 | Given successful investigation/planning, when coding policy is absent/revoked, then no implementation task is dispatched |
| VNX-AC-05 | Given fabricated agent test output, disabled test discovery or tampered candidate, when protected verification runs in an unprivileged sandbox, then no false PASS/advance occurs |
| VNX-AC-06 | Given failing tests, when correction budget remains, then an authorized rework attempt may occur; otherwise BLOCK/ESCALATE |
| VNX-AC-07 | Given worker crash, expired lease and late result, when processing resumes, then stale result cannot mutate authoritative workflow state |
| VNX-AC-08 | Given source/target revision or policy changed before GitLab write, then executor refuses stale/unauthorized delivery |
| VNX-AC-09 | Given **branch push or Draft MR creation** succeeded but reply was lost, then its individual ledger step becomes UNKNOWN; reconcile remote ownership/head before retry, never blindly overwrite |
| VNX-AC-10 | Given successful local verification and permitted delivery, then Draft MR is created with traceable candidate revision; no merge/approval occurs |
| VNX-AC-11 | Given a Viewer or agent attempts Owner-only control or credential access, then backend/process policy denies it |
| VNX-AC-12 | Given local-model failure, then no unapproved cloud inference occurs, and workflow reports bounded failure/escalation |
| VNX-AC-13 | Given completion/cancellation/crash, temporary source/workspace cleanup is enforced and recoverably scavenged |
| VNX-AC-14 | Given CI status is pending or failed, dashboard does not label CI passed or source merged; behavior follows explicit verification/status policy |

## 8. Retention and security policy inheritance

The effective **M01** RET-001..008, OPS-001..004 and SEC-009 remain binding **within their approved scope**. Existing M01 comment Publication requirements (PUB-001..009, Shadow, Auto-Publish default OFF) are not changed by this vNext draft.

New data classes require a separately reviewed lifecycle policy: Jira ticket snapshots/PII, ephemeral Git worktrees, agent transcripts/tool logs, candidate patches, test outputs, Code Delivery ledger and remote MR metadata. **Do not silently apply the M01 finding/Publication TTLs to new vNext classes.** Default to no long-term raw-source/session retention; clear temporary data on finish/failure/cancel and scavenge crashes. Exact TTL and backup recovery treatment remain OPEN before real sensitive-data execution.

## 9. Execution prerequisites / unresolved policies (not blockers to documenting the design)

- Precise permitted Jira intake mechanics, ticket-to-repository mapping and authorization defaults.
- Limits for correction loops, stage budgets, CI feedback behavior and independent quality thresholds.
- Real GitLab Draft MR/write permission contract; credentials and branch rules.
- Retention/visibility classification for new vNext data (including Viewer read scope).
- Local model/OpenHands feasibility and sandbox/egress verification.

These must not be represented as implemented or runtime-verified from design approval alone.

## 10. Legacy authority

- `../M01_CURRENT_REQUIREMENTS.md` — effective M01 requirements remain in force.
- `../v0.1/M01_MVP_SCOPE.md` — historical 2026-10-15 requirements; only previously approved amendments supersede conflicting legacy behavior.
- `../../governance/vnext/CHANGE_PROPOSAL.md` — rationale and precise vNext scope boundary.
- `../../architecture/adr/ADR-001.md`, `../../architecture/adr/ADR-002.md` — historically approved architecture decisions and technical safety invariants.

## 11. Interim fail-closed treatment for unresolved vNext data and CI policy

- **New Jira descriptions, raw repository source, patches, tool outputs and agent transcripts:** do not extend existing M01 Viewer permissions to these classes. No default indefinite persistence; any real-data workflow must satisfy a later explicit classification/retention/access decision before enrollment. Synthetic fixtures may be used for authorized future PoCs once the docs gate is complete.
- **CI status:** independent local verification is required before Draft MR creation. An observed GitLab CI outcome is tracked independently; `Draft MR created` cannot be labeled `CI passed`. An automated CI correction loop is **not** authorized as an initial-slice dependency until explicitly scheduled under Project Control.
- **External write policy:** policy-based internal stage advancement never grants comment publication, merge/approval, protected-branch modifications or production deployment. Those are separate authority boundaries.

The above is restrictive handling of unresolved decisions, not evidence that the necessary controls or data lifecycle have been implemented.

## 12. Additional minimum policy gates from independent review

The following are **required security/engineering prerequisites before their corresponding execution**, not evidence that they are implemented or approved real-data policies:

| Gate | Minimum fail-closed requirement |
|---|---|
| Synthetic agent execution | Pin SDK/local-provider profile; separate supervisor authority from tool process; no real credentials/source, host home or unrestricted egress; protected verifier controller and sandbox |
| Real Jira intake | Explicit scoped Jira read-egress amendment, ticket/repository allowlist, field classification/retention; reject arbitrary issue URLs/attachments and untrusted policy instructions |
| Real source/credentials | Approved data classes/TTL/viewer access, credential and service identity scopes, SDK transcript cleanup, artifact lifecycle/backup assumptions |
| First remote push/MR | CI enrollment and secret/deploy protection, safe MR template, source/base/candidate/target SHA binding, branch ownership collision rules, separate push/MR ledgers, UNKNOWN/revocation handling |
| Slice acceptance | Independent protected tests and reviewer rubric on exact candidate; no-tests-collected is not PASS; track GitLab CI separately from local verification and human merge |

See [canonical execution contract draft](../../architecture/vnext/EXECUTION_CONTRACTS_AND_GATES.md). This extends the **verification duties** for VNX-REQ-01..16; it does not create authorization for unattended real-data execution or a new cloud policy.
