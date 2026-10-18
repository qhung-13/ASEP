# ASEP M01 - Architecture Decision Register (Consolidated v0.1)

> **Repository-document status: OWNER-ACCEPTED REGISTER CONSOLIDATION (2026-10-18; M00-04).**
> **Approved source decisions:** ADR-001/002; MD-01..10; TSE-01..06C at their *individual* approval scopes, consolidated in M00-03. The register structure was accepted by the Owner on 2026-10-18.  
> **Provenance:** Recovered register, Module Decisions and TSE notes. Original individual MD/TSE decision transcripts and full comparison tables are not verified as available. This document is **not** verbatim re-export of those sources.

## 1. Decision status taxonomy

| Status | Meaning |
|---|---|
| APPROVED - DECISION | Owner approved the stated architectural choice, constrained by its scope and date |
| APPROVED - DIRECTION | Architectural/technology direction accepted; exact runtime/tooling details still deferred |
| DRAFT DOCUMENT STRUCTURE | Possible future ADR document title, **not** a newly approved technical choice |
| DEFERRED | Not chosen; requires future investigation/design/Owner decision |
| OPEN / NOT VERIFIED | Technical behavior/risk cannot be claimed resolved without direct evidence |
| RECONSTRUCTED TEXT | A source summary of approved choices; not an independently authenticated original transcript |

Approving the **register structure** does not approve missing ADR-003..007, new implementation details, or runtime viability.

## 2. Formal ADR decisions

| ADR | Approved decision | Date and scope | Explicit caveat |
|---|---|---|---|
| [ADR-001](../architecture/adr/ADR-001.md) | Modular monolith backend with independently running API and Worker processes | 2026-10-16, architecture | No framework/database/queue/model was chosen **at that time**; original verbatim file missing |
| [ADR-002](../architecture/adr/ADR-002.md) | Revision-aware polling/admission; durable claims/leases/fencing/recovery; independent Publication ledger, serialized writes, reconciliation | 2026-10-17, architecture | Consolidated reconstructed text; no exactly-once remote effect claim or finalized library/schema |

Both ADRs were retained in the Final Architecture Baseline approval of 2026-10-18. Their decisions are approved, but **this repository packaging and reconstructed prose still require review**.

## 3. MD-01..MD-10 - approved module/responsibility decisions

| ID | Module/responsibility boundary | Invariant / what it does not authorize | Deferred |
|---|---|---|---|
| **MD-01** | Separate Review and Publication; Review owns validated findings/state, Publication owns GitLab policy, writes/ledger/reconciliation | Review has no uncontrolled GitLab write path | Exact handoff contracts/schema |
| **MD-02** | Capability-specific GitLab read ports for Detection/Review and write port for Publication | Do not expose all-powerful client/credentials to reasoning | Actual PAT scopes, adapter and process isolation |
| **MD-03** | Review owns read-only context selection, partial coverage, provenance and finding/evidence validation | No manufactured full-coverage claim; model output is untrusted | Context budget, selection, finding schema |
| **MD-04** | Identity & Access owns ASEP business authorization; Keycloak later selected for identity/authentication; Viewer Global Full Read-Only amendment applies | Viewer cannot mutate/issue workflow commands or see raw secrets; backend enforces | Role mapping, provisioning, sessions/revocation |
| **MD-05** | Integration Management owns connection/credential references, tracked repos, configuration/removal lifecycle; Detection owns checkpoints, Publication owns auto policy | Removing integration must follow OPS-004; no credential duplication | Secret storage/health checks/schema |
| **MD-06** | Thin application coordination and durable, idempotent Review-to-Publication handoff | Coordination is not the owner of all domain state; Review completion grants no external write | Exact outbox/consumer/transaction machinery |
| **MD-07** | Evaluation owns Human Evaluation and independent AI quality measurements | Cannot modify findings, activate policy or automatically authorize Auto-Publish | Gold dataset, numerical quality gates, feedback TTL |
| **MD-08** | Query Composition assembles module-owned read projections with authorization | No shared-database bypass or independent 'Dashboard god-domain' | Read models, performance, history TTL |
| **MD-09** | Domain modules own durable history; shared sanitized telemetry/audit is diagnostic | Logs/traces are not substitutes for Publication Ledger or durable state | History schema/TTL, telemetry backend |
| **MD-10 (Revised)** | Domain-owned lifecycle distinguishes temporary context, optional approved reusable cache, durable business/audit/ledger data | No cross-review source/LLM KV cache by default; retention/cleanup must be respected | Cache policy, detailed history/evaluation TTL, scavenger implementation |

The Owner approved these decisions by M00-03 final signoff; the text above is **recovered decision-level summary**, not a verbatim MD document. Refer to [MD inventory](../sources/recovered/03_Module_Decisions_MD01_MD10_RECOVERED.md).

## 4. Technology Stack Evaluations - TSE-01..TSE-06C

| ID | Approved selection/direction | Scope and trade-off | Explicitly deferred |
|---|---|---|---|
| **TSE-01** | Java + Spring Boot + Spring Modulith | Selected backend platform for a modular monolith; JVM cost and Java learning curve accepted | Versions, build tool, modules/packages |
| **TSE-02** | PostgreSQL | Selected durable relational store/transactions | Schema, migrations, JPA/Hibernate vs explicit SQL/JDBC split |
| **TSE-03** | PostgreSQL-backed durable jobs and handoff; explicit PostgreSQL Outbox direction | Direction; single durable datastore reduces separate infrastructure but requires locking/transaction verification | db-scheduler PoC vs JobRunr OSS; scheduler/outbox details |
| **TSE-04** | Replaceable, local-only inference port | Direction; privacy/control traded for resource and quality uncertainty | Ollama initial PoC vs llama.cpp; model, quantization, benchmark |
| **TSE-05** | Next.js App Router + TypeScript | Selected frontend/Web UI and BFF direction | UI kit, query/state libraries, cache/rendering strategy |
| **TSE-06A** | Self-hosted Keycloak | Selected identity provider, separates IdP identity from ASEP business permissions | Realm/client provisioning, deployment and role mapping |
| **TSE-06B** | OIDC Authorization Code + PKCE S256, confidential Next.js BFF, Spring Resource Server | Approved auth/session architecture direction; server-side token protection and API JWT validation | Session store/TTL, refresh/revocation, CSRF, issuer/hostname details |
| **TSE-06C (Revised)** | Single Ubuntu host; Docker Compose initial candidate; hybrid option incl. host-native Ollama PoC; K3s/Kubernetes/Jenkins learning directions | **Direction**, not final deployment topology; Rancher optional | Ports/TLS, resource and credential isolation, GPU, roadmap scheduling |

**Not approved as mandatory M01 infrastructure:** Redis, RabbitMQ, Kubernetes, Rancher, Jenkins, cloud AI provider or an arbitrary queue stack. Additional dependencies need a justified use case and Owner decision.

Full original TSE alternative-scoring sheets and approval wording are **not recovered**. The above is derived from the [TSE recovery notes](../sources/recovered/04_Technology_Evaluations_TSE01_TSE06C_RECOVERED.md).

## 5. ADR-003..ADR-007 - proposed document structure ONLY

| Candidate | Working title | Status |
|---|---|---|
| ADR-003 | Backend & Persistence | DRAFT DOCUMENT STRUCTURE; not final ADR decision |
| ADR-004 | Frontend Architecture | DRAFT DOCUMENT STRUCTURE; not final ADR decision |
| ADR-005 | Identity & Session Architecture | DRAFT DOCUMENT STRUCTURE; not final ADR decision |
| ADR-006 | Local AI Integration | DRAFT DOCUMENT STRUCTURE; not final ADR decision |
| ADR-007 | Deployment Strategy | DRAFT DOCUMENT STRUCTURE; not final ADR decision |

Future ADR authoring must not contradict ADR-001/002 or duplicate a decision and falsely attribute a second approval. TSE-03 detailed Outbox design may belong under ADR-002 unless a distinct decision is justified.

## 6. Open/deferred implementation register

| Topic | State and required future work |
|---|---|
| Scheduler and queue | db-scheduler / JobRunr OSS PoC and transaction semantics **NOT VERIFIED** |
| ORM and concurrency SQL | Choose/JPA/SQL boundaries after PostgreSQL concurrency tests |
| Outbox | Exact transactional write, dispatcher, retry and consumer idempotence not finalized |
| AI runtime and model | Ollama/llama.cpp comparison, VRAM/context/accuracy/latency benchmark not run |
| Keycloak | OIDC/PKCE issuer/audience/session/token lifecycle and secure provisioning need implementation tests |
| GitLab | PAT privileges, real API behavior, MR diff positions and stale-race limit not verified |
| Network isolation | Local-only inference, credential isolation and restricted egress not runtime verified |
| Retention and backup | TTL/scavenger/crash cleanup and ledger expiry; backup/restore strategy open |
| AI quality gates | Independent numeric thresholds and safe Auto-Publish activation not approved/verified |
| Deployment topology | Exact API/Worker/Publication service roles and ports not finalized |
| Architecture V-01..V-14 | **All OPEN/NOT VERIFIED**; track via verification backlog |

## 7. Approval provenance and adoption

- 2026-10-16: ADR-001 decision approved (without selecting framework/DB/queue/model).
- 2026-10-17: ADR-002 consolidated architectural decisions approved; MD decisions approved across M00-03 design discussions.
- By 2026-10-18: TSE-01..TSE-06C approved at respective scopes, MD-01..10 retained, Testing & Observability and Traceability planning baselines approved.
- 2026-10-18: Owner approved final M00-03 architectural design baseline, including approved decisions and **continued OPEN risks**.
- 2026-10-18: Owner accepted ADR/register consolidation **structure**; no new formal ADR-003..007 approvals.

This **new repository register** needs Owner comparison/acceptance before use as the canonical file. Approval of a decision is not proof that the exact text in a newly authored register was itself reviewed.

## 8. Related sources

- [Original TDR](../discovery/TECHNICAL_DISCOVERY_REPORT.md)
- [ADR register recovery](../sources/recovered/07_ADR_Architecture_Decision_Register_RECOVERED.md)
- [Module decisions recovery](../sources/recovered/03_Module_Decisions_MD01_MD10_RECOVERED.md)
- [TSE recovery](../sources/recovered/04_Technology_Evaluations_TSE01_TSE06C_RECOVERED.md)
- [System Architecture](../architecture/ARCHITECTURE.md)
