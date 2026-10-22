# ASEP vNext — Owner-Selected Decisions & Open Items

**Decision date:** 2026-10-22
**Status:** OWNER-ACCEPTED DESIGN DIRECTIONS; documentation update requested by Owner. **No runtime verification or Ubuntu Git commit is implied.**
**Precedence:** This record governs **vNext Jira-to-Draft-MR scope only**. Existing approved M01 review/comment publication and historical M00 decisions are preserved, except the explicitly described future-scope architecture changes.

## 1. Accepted product policies

| ID | Owner-selected policy | Guardrails |
|---|---|---|
| P-01 | Prioritize a bounded eligible Jira ticket → independently verified GitLab **Draft** MR vertical slice | Existing M01 work remains historical/partially reusable; never mark it complete or cancelled by inference |
| P-02 | Allow automated investigation → plan → sandboxed coding → independent tests/review → Code Delivery, when scoped policy and evidence gates pass | Default deny, bounded budgets, revision freshness and current authorization; **no auto merge/approve/deploy**; GitLab review-comment Auto-Publish is a separate policy |
| P-03 | Initial vNext model inference remains **local-only** | Claude/cloud and external telemetry default OFF; future opt-in requires standalone Owner-approved CR and security/privacy gates |

## 2. Owner-selected technical directions for vNext

| ID | Direction | Evidence status / ownership |
|---|---|---|
| VN-T01 | Reuse **Next.js + TypeScript**, **Java + Spring Boot** and **Keycloak** in Application & Integrations | Existing M01 foundation/authorization direction retained; code integration not reverified |
| VN-T02 | **Go + PostgreSQL custom minimal durable workflow/task queue**; no River/Temporal required initially | Owner selected option C; leases/fencing/recovery NOT VERIFIED |
| VN-T03 | **Python + OpenHands SDK** in isolated Agent Execution via ASEP-owned adapter | Owner selected; SDK/Ollama/sandbox performance NOT VERIFIED |
| VN-T04 | One PostgreSQL instance initially, domain-owned schemas/roles; Keycloak identity data separate; agent runner has no business DB access | **Engineering proposal within chosen direction**; exact grants/migrations NOT APPROVED/VERIFIED |
| VN-T05 | Internal versioned REST/JSON and Go-owned pull/claim/heartbeat/result contracts initially | **Engineering proposal**; service authentication/actual schemas OPEN |
| VN-T06 | Trusted External Action Executor handles privileged Code Delivery with an independent operation ledger | **Proposed implementation boundary**; NOT a replacement for existing M01 Review Publication Ledger |
| VN-T07 | Single Ubuntu host with Compose initial candidate; local model gateway | Isolation, GPU/model selection, deployment NOT VERIFIED |

## 3. Scope-specific precedence over historical records

- `ADR-001` remains the historical M01 decision (one backend codebase with API and worker processes). **ADR-VN-01** removes the one-codebase constraint **for vNext** while retaining modularity and separate lifecycles.
- `TSE-01` remains correct for Java Application. Go Orchestration and Python/OpenHands Agent Execution supersede the assumption that all vNext workers use Java.
- `ADR-002` durable claims, fencing and publication safety remain binding; the new Code Delivery operation is **distinct** from M01 comment publication.
- `TSE-02/TSE-03` PostgreSQL direction remains; Go uses an initial minimal SQL task queue, not a required scheduler library.
- `REQ-009` local-only and the no-external-AI/telemetry constraints remain unchanged. Historical M01 `SEC-009` remains intact; **vNext requires an explicit narrow Jira-read egress amendment** before real Jira access (see [VNX-SEC-EGRESS-01](CHANGE_PROPOSAL.md#proposed-vnx-sec-egress-01--narrow-jira-read-exception-owner-policy-confirmation-required-before-real-jira-access)).
- CR-001 Shadow/conditional Auto-Publish, Viewer Global Full Read-Only within approved M01 data scope, RET/OPS policies and historical V-01..14 remain binding **within their approved scopes**.
- The historical M00–M05 roadmap records are not rewritten. P-01 changes priority for future execution; official ticket allocation belongs to Project Control.

## 4. Open decisions (do not silently infer answers)

**Authority taxonomy:** P-01/P-02/P-03 and VN-T02/VN-T03 were expressly Owner-selected directions. VN-T04..VN-T07 contain engineering design proposals/obligations and **are not approval of every named field, database grant or transport topology**. The 2026-10-22 documentation-update instruction permits drafting technical details, not a claim of runtime proof or accepted new real-data privileges.


Exact Jira admission trigger/scope; **proposed VNX-SEC-EGRESS-01 Jira-read exception (not yet independently authorized)**; new Jira/agent retention and Viewer classification; optional CI feedback/rework extent; internal service authentication; numeric budgets/quality gates; exact migrations/APIs; token permissions; model/VRAM feasibility; sandbox/network protections; CI execution risks; and backup/restore must be designed or verified before real untrusted/sensitive use or delivery readiness.

Until new data access/retention rules are adopted, default to **deny new Viewer access**, minimize temporary sensitive source/artifacts, and restrict future PoCs to appropriately scoped fixtures. Technical uncertainty never grants additional autonomy or external-write authority.

## 5. Evidence and next gate

The Owner asked to update documentation according to selected decisions on 2026-10-22. These records document that instruction, not a claim that the owner line-by-line reviewed newly generated files or that live files were committed. After documentation is applied and Git diff reviewed, record the commit evidence in Project Control; only then may a separately authorized PoC/implementation ticket begin.

See [change record](CHANGE_PROPOSAL.md), [vNext requirements](../../requirements/vnext/MVP_SCOPE.md), [system architecture](../../architecture/vnext/SYSTEM_ARCHITECTURE.md), [ADR-VN-01](../../architecture/adr/vnext/ADR-VN-01-COMPONENT-BOUNDARIES.md), [ADR-VN-02](../../architecture/adr/vnext/ADR-VN-02-DURABLE-ORCHESTRATION.md), [ADR-VN-03](../../architecture/adr/vnext/ADR-VN-03-AGENT-EXECUTION.md), [ADR-VN-04](../../architecture/adr/vnext/ADR-VN-04-DATA-AND-COMMUNICATION.md), [verification](../../verification/vnext/TRACEABILITY_AND_RISKS.md) and [work sequence](ROADMAP_PROPOSAL.md).
