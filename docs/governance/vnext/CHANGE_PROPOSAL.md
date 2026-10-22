# CP-VNEXT-01 — ASEP Evidence-Gated SDLC Vertical Slice

**Prepared:** 2026-10-22
**Status:** OWNER-ACCEPTED vNext scope and policy directions (P-01/P-02/P-03, 2026-10-22); updated documentation package prepared at Owner request. Local-repository adoption/commit NOT VERIFIED.
**Change class:** Product scope + execution order + architecture/technology evolution.
**Supersession rule:** Later approved records may supersede specified scope, but never rewrite historical approved text or claim retroactive approval.

## 1. Context and motivation

ASEP is a personal, self-hosted engineering portfolio project. The effective M01 requirement package describes a GitLab AI Merge Request Reviewer; its initial scope excludes Jira investigation, autonomous code changes and Draft MR creation. The historical approved M00–M05 roadmap sequences review before the Jira/implementation/orchestration milestones.

The Owner wants to investigate an integrated, **bounded** outcome instead of implementing every component independently: an eligible Jira ticket proceeds through admission, investigation, planning, isolated implementation, trusted tests/review and controlled GitLab Draft MR creation, with human review/merge. Reliability, security and measurable outcomes are central. This is **not** authorization to build a generalized AI development team or cloud SaaS.

## 2. Owner-accepted vNext policy directions

| ID | Accepted direction | Consequence / explicit exclusion |
|---|---|---|
| P-01 | Prioritize Jira → Verified Draft MR vertical slice | New execution proposal; do not declare legacy M01 complete or cancel existing tickets implicitly |
| P-02 | Evidence-gated autonomous stage transitions under Owner-scoped authorization | No per-stage approval requirement when explicitly authorized; reject blocked/revoked/unsafe actions; humans alone merge |
| P-03 | Initial vNext local-only model inference | Claude/cloud escalation remains out of scope pending a separately reviewed security/requirements CR |

The Owner explicitly directed this documentation update on 2026-10-22. The policy directions are accepted at design level; exact numeric budgets, schemas, integrations, isolation, runtime quality, and GitLab capabilities remain OPEN / NOT VERIFIED.

## 3. Baseline-to-vNext delta

| Existing source / rule | Retain | vNext change recorded |
|---|---|---|
| Historical Project Vision M00-01 | Self-hosted portfolio, secure SDLC, human-controlled consequential operations | Updated target stage-approval policy P-02, without changing human merge authority |
| `requirements/M01_CURRENT_REQUIREMENTS.md` | M01 reviewer, GitLab polling, CR-001 Shadow/conditional Auto-Publish, Viewer amendment, freshness | New **separate vNext requirements**, not an in-place overwrite of M01 semantics |
| `ADR-001` | Clear modules, API/worker lifecycle isolation, durable state | Supersede the one-backend-codebase deployment assumption for vNext via ADR-VN-01 |
| `ADR-002` | Admission/leases/fencing, recoverability, publication ledger and reconciliation | Apply invariant principles to new Go orchestration and Draft MR **Code Delivery**; preserve the legacy Publication Ledger |
| `TSE-01` | Spring Boot/Spring Modulith for Application API/integrations | Split Go orchestration and Python/OpenHands execution out of former Java Worker responsibilities |
| `TSE-02`, `TSE-03` | PostgreSQL durability, transaction/outbox direction | Domain-owned schemas and custom Go task queue, no mandatory Redis/River/Temporal |
| `TSE-04`, `REQ-009`, `SEC-009` | Local-only inference; M01 egress permits approved GitLab and local services | Local-only/cloud-off retained; proposed **vNext-specific Jira read-egress exception** requires explicit adoption, not silent modification of historical SEC-009 |
| `TSE-05`, `TSE-06A/B/C` | Next.js, Keycloak/OIDC, single Ubuntu host/Compose direction | Retain; internal service auth and deployment isolation remain to be verified |
| `MD-01..10` | Review/Publication separation, domain-owned history, query auth, retention | Explicit vNext mapping; no replacement of existing M01 Publication policy by Code Delivery |
| Roadmap v0.1 | Immutable historical milestone meanings and achieved evidence | Owner-accepted priority for future work; formal ticket/milestone reassignment remains a Project Control activity |

## 4. New privileges and approvals

### Authorized automation (P-02)

- Owner-configured repository and workflow eligibility must be explicit and **default deny**.
- Admission records policy version, actor/trigger, repository and source revision. A ticket alone never grants credentials or model autonomy.
- Go may advance stages only when the relevant policy and independent evidence gates pass.
- Policy revocation, budget exhaustion, insufficient evidence, stale source or security failures block/escalate execution.
- Automatic stage progression is **not** permission to merge, approve an MR, deploy, change protected branches or publish review comments.

### New Code Delivery boundary

- Draft branch push and GitLab Draft MR creation require a **separate** authorized Code Delivery operation, not the M01 comment Publication capability.
- The trusted external-action executor checks current policy/permissions/source revision and controls credentials, durable operation IDs, unknown-outcome reconciliation and remote freshness.
- Agent and sandbox have no access to privileged Jira/GitLab write tokens.
- Branch push and Draft MR creation are **separate ledger steps**; each can be CONFIRMED, UNKNOWN or BLOCKED. CI triggered by a push/Draft MR must be reviewed for protected secrets/deploy triggers before remote writes.

### Human final authority

- Human review and merge remain outside autonomous execution; the system must not auto-merge or auto-approve.
- Existing conditional Auto-Publish for **review comments** continues under CR-001 (default OFF, independent safety/quality gates). It must not be inferred from P-02.

### Cloud policy (P-03)

- No cloud inference or external telemetry in initial vNext; no runtime fallback to Claude when Ollama fails.
- A future opt-in would require a standalone Owner-approved CR addressing repository authorization, outbound data classes, prompt/context minimization, consent, budgets, retention, audit, egress allowlisting and testing.

### Proposed VNX-SEC-EGRESS-01 — narrow Jira read exception (Owner policy confirmation required before real Jira access)

P-01 chooses a Jira-backed product outcome, but historical **SEC-009** was written for M01 GitLab/local-service egress. The vNext network policy therefore **must explicitly add** only the Owner-enrolled Jira Cloud API origin for the trusted **Java Jira Read Adapter**, with the minimum read methods, access scopes, bounded fields and size limits necessary for approved tickets. This is a **vNext-only proposed amendment**, not an assertion that original SEC-009 authorized Jira.

- Do not grant Jira access to Go, Python/OpenHands, coding sandboxes, verifier child processes or model plugins. Ticket bodies, links and attachments are untrusted; no automatic following of arbitrary URLs, redirects, attachments or remote tool/plugin configuration.
- Prevent redirects/SSRF to unapproved origins; verify credentials, allowlist, DNS/network route enforcement and normalized text/ADF boundaries in implementation.
- Maintain local-only inference, no cloud fallback, no external telemetry; provisioning/dependency downloads require separately controlled action, not unrestricted runtime egress.
- **Until this scoped exception and data-access rules are adopted, synthetic Jira fixtures only**. Record exact approved origin/scope at the real-data integration gate, not here as an invented hostname or approval.

## 5. Accepted minimum outcome boundaries (design level)

Success is **not** a model's `success: true` response. The vertical slice must provide:

1. Eligible, deduplicated ticket admission and revision snapshot.
2. Investigation/planning results with referenced evidence and coverage limits.
3. Restricted implementation workspace with immutable candidate artifact/revision identity.
4. Independent test/security/review evidence; bounded correction or escalation for failures.
5. Verified, authorized and reconciled GitLab **Draft** MR creation (or explicit blocked/unknown state).
6. Accurate UI/read-side outcome and audited recovery path; human alone merges.

Draft MR created is distinct from CI passed, merged, and task fully resolved. CI status must not be invented or inferred; the exact CI feedback/rework acceptance gate is a downstream design decision.

## 6. Conflicts and supersession treatment

| Ref | Change | Handling |
|---|---|---|
| CR-C01 | M01 out-of-scope Jira/coding/Draft MR vs vNext | Separate vNext document and formally adopted roadmap proposal |
| CR-C02 | Historical pre-implementation Human Approval vs P-02 | Explicit bounded authorization-policy amendment, not blanket removal of human control |
| CR-C03 | GitLab comment writes vs branch/MR writes | Separate Code Delivery policy/ledger and capability boundaries |
| CR-C04 | Potential cloud inference vs REQ-009/SEC-009 | Retain local-only, defer cloud CR |
| CR-C05 | ADR-001 one backend codebase | New superseding decision scoped to vNext only |
| CR-C06 | M01 retention TTLs vs new Jira/artifact classes | New retention policy needed before real sensitive data is stored durably |
| CR-C07 | Legacy M01 progress and roadmap allocation | Reconcile live Git and Project Control before modifying ticket statuses |
| CR-C08 | SEC-009 old egress list vs new Jira Cloud intake | Proposed scoped vNext Jira read-egress amendment; historical M01 SEC-009 is unchanged |

## 7. Non-goals

No auto-merge/auto-approval, deployment, arbitrary external-service writes, cloud default inference, uncontrolled multi-agent team, Kubernetes migration, Redis/RabbitMQ, heterogeneous database engines, generalized workflow framework, organization multi-tenancy or production-readiness claims in the initial slice.

## 8. Open implementation decisions and verification

- Exact automation eligibility configuration and authorization protocol.
- Exact Jira intake mechanism (manual vs bounded polling), ticket/repository mapping and admission criteria.
- Explicit policy confirmation for the narrow Jira egress exception below, before connecting to real Jira; otherwise use synthetic fixtures only.
- Code Delivery credential scopes, GitLab API behavior and remote-side idempotency limits.
- CI feedback loop acceptance criteria and when a task may be declared complete.
- Retention for new Jira metadata, patch artifacts and agent sessions; backup/recovery policy.
- Internal service auth mechanism, execution isolation, API schema and budgets.
- Local Ollama/OpenHands compatibility and coding quality on hardware.

All implementation-specific points remain OPEN until designed, approved where normative, and verified where technical. This does not reopen P-01/P-02/P-03. Historical V-01..V-14 remain OPEN.

## 9. Approval/adoption gates

**Gate A (accepted direction):** Owner accepted P-01/P-02/P-03 and instructed the assistant to update documentation using previously selected technologies on 2026-10-22.
**Gate B (repository adoption pending):** Apply to live repository, inspect `git diff` and documentation consistency, then record commit evidence.
**Gate C (Project Control pending):** Assign/reprioritize tickets without rewriting historical roadmap status.
**Gate D (future work):** Only after documentation adoption may authorized PoCs/implementation start; technical verification remains separate.

### Source references

- `../../requirements/M01_CURRENT_REQUIREMENTS.md`
- `../../requirements/v0.1/PROJECT_VISION.md`
- `../../architecture/adr/ADR-001.md`
- `../../architecture/adr/ADR-002.md`
- `../DECISIONS.md`
- `../IMPLEMENTATION_ROADMAP.md`
- `../../verification/TRACEABILITY_AND_RISKS.md`
