# 03 — MD-01..MD-10 Module Decisions — Recovered Decision Inventory
**Overall status:** Decisions MD-01 through MD-10 APPROVED in M00-03 (2026-10-17 context).
**Important:** Full original 10 individual decision messages and exact wording have NOT been recovered. Each subsection below is an evidence-based summary; do not use it as an official verbatim decision record. Approval dates are based on conversation-history recovery, not an exported signed register.

## MD-01 — Separate Review & Publication Modules
**Decision approved:** Review owns Review lifecycle/validated findings, Publication owns external GitLab-write policy, publication state/ledger and reconciliation. Modules communicate via durable handoff and explicitly authorized read contracts. Review may not call uncontrolled GitLab write operations.
**Invariant:** AI analysis completion never implies write authorization.
**Deferred:** Precise handoff persistence/protocol, schema, runtime mapping.

## MD-02 — Capability-Specific GitLab Ports & Adapters
**Decision approved:** Separate read-only GitLab capabilities (Detection/Review) from controlled comment-write capabilities owned by Publication. Explicit contracts allow fake adapters/integration tests. Least-privilege credentials and security boundaries are required.
**Invariant:** Detection/Review do not acquire GitLab write capability through a shared all-powerful client.
**Deferred:** PAT choice, actual permission scopes, physical credential/process isolation, API runtime tests.

## MD-03 — Review-Owned Context & Finding Validation
**Decision approved:** Review owns source/diff context preparation, partial coverage/provenance and validated findings. Use a read-only GitLab context port and local inference adapter. Model outputs are untrusted input, not commands.
**Invariant:** Evidence-based findings must be linked to inspected revision; incomplete context not represented as full coverage.
**Deferred:** Exact schema, token budget, model/runtime, file-selection strategy, execution isolation.

## MD-04 — Identity & Access Boundary (later amended by TSE-06A)
**Decision approved at logical level:** Central authorization ownership for account/Owner/Viewer business roles and allowed actions; workers use authorized system configuration rather than borrowed browser sessions; Publication revalidates Owner policy.
**Later approved amendment:** Keycloak owns identity, authentication, credentials and IdP sessions; ASEP owns business authorization, permissions and Owner-initiated provisioning integration.
**Viewer amendment:** Global Full Read-Only to retained ASEP-managed business data, not repository grants; no mutations/config/feedback/secrets.
**Deferred:** Keycloak role mapping, session store, revocation/provisioning implementation.

## MD-05 — Integration Management as Logical Module
**Decision approved:** Integration Management owns GitLab connection lifecycle, credential references, tracked repositories and integration configuration. Identity & Access owns user/business permission; MR Detection owns polling/checkpoints; Publication owns Auto-Publish policy. Removal triggers OPS-004 lifecycle and retains published history only by approved TTL.
**Deferred:** Secrets strategy, token details, integration health intervals, schema and process isolation.

## MD-06 — Thin Application Coordination + Durable Handoff
**Decision approved:** Thin orchestration/application coordination invokes explicit module commands/contracts without becoming the owner of all domain state. Review and Publication remain independent durable operations with idempotent at-least-once handoff/consumer admission (e.g. transactional outbox equivalent).
**Invariant:** Domain modules own their transaction boundaries and decisions.
**Deferred:** Broker/queue/job engine, transaction mechanism, exact process mapping.

## MD-07 — Independent Human Evaluation & Review Quality Boundary
**Decision approved:** Dedicated logical Evaluation module owns human feedback, assessments, quality metrics and reports; Review owns finding content and model/config provenance. Evaluation consumes read-only contracts.
**Invariant:** Evaluation does not itself mutate Review findings, activate policy, or automatically authorize Auto-Publish.
**Deferred:** Ground-truth schemas, numerical thresholds, feedback TTL, exact evaluation workflow.

## MD-08 — Dashboard Query & Read Composition Boundary
**Decision approved:** Thin application query-composition layer combines Review/Publication/Evaluation module-owned projections via read-only interfaces. No separate god-domain Dashboard module or uncontrolled shared read database. Authorization enforced before data disclosure.
**Viewer amendment:** Viewer Global Full Read-Only for sanitized retained business data/execution history (including unpublished findings and evaluations).
**Observability scope:** Coverage, input context metadata, model/prompt version, finding evidence, retries, publication state, evaluation, within retention limits.
**Deferred:** Read model implementation, query performance and detailed execution retention.

## MD-09 — Domain-Owned Execution History + Shared Telemetry/Audit
**Decision approved:** Modules persist domain execution facts/states; shared observability infrastructure emits sanitized logs, metrics/traces and audit material. Publication Ledger remains the source of truth for external GitLab writes. Critical transitions must be durable/recoverable.
**Invariant:** Logs/traces are not authoritative replacements for durable workflow state or ledger.
**Deferred:** History schema/TTL, telemetry backend and retention implementation.

## MD-10 — Revised Domain-Owned Data Lifecycle & Retention Coordination
**Decision approved (Revised):** Three classes are distinguished: (1) temporary execution source/context artifacts, (2) optional managed reusable cache requiring independent permission, (3) durable business/audit/ledger data. Domain modules own lifecycle semantics, with shared coordination for TTL/cleanup. Retain RET-001..008 and OPS-001/004.
**Invariant:** No persisted cross-review source/context or inference KV cache without a separate approved data/retention/security policy. Crash leftovers must be scavenged.
**Deferred:** Cache technology, cache policy, detailed-history/Human Evaluation TTL, exact cleanup implementation.

## Decision provenance summary
- MD-01..MD-10 are Owner APPROVED by the time M00-03 architecture was approved 2026-10-18.
- Some individual decisions are traceable to conversations 2026-10-17; full original messages not in the current file corpus.
- **Do not use these summaries to invent missing method signatures/interfaces or approve deferred choices.**
