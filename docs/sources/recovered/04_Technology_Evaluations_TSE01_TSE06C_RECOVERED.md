# 04 — Technology Stack Evaluations TSE-01..TSE-06C — Recovered Notes
**Status:** Approved technologies/directions confirmed by M00-03 Execution Summary and M00-04 handoff. The original full evaluations (alternative scoring, full Owner wording, section-by-section rationale) have not been recovered. Treat comparison notes as RECOVERED/SYNTHESIZED, not verbatim original evaluations.

| ID | Decision | Approval scope | Key deferred |
|---|---|---|---|
| TSE-01 | Java + Spring Boot + Spring Modulith | APPROVED selected backend direction | Versions/build, module packages |
| TSE-02 | PostgreSQL | APPROVED database direction | ORM/SQL strategy, schema/migration tool |
| TSE-03 | PostgreSQL-backed durable job execution & handoff | APPROVED architectural direction | db-scheduler/JobRunr PoC; explicit Outbox implementation |
| TSE-04 | Local-only inference via replaceable Inference Port | APPROVED architectural direction | Ollama/llama.cpp, model, benchmark |
| TSE-05 | Next.js App Router + TypeScript | APPROVED frontend direction | UI/state libraries, caching |
| TSE-06A | Self-hosted Keycloak IdP | APPROVED | Version, provisioning/deployment |
| TSE-06B | Authorization Code + PKCE S256 + confidential-client Next.js BFF; Spring Resource Server | APPROVED architectural direction | Sessions, refresh/revocation, role mapper |
| TSE-06C | Compose initial single-host candidate, DevOps learning track | APPROVED architectural direction | Exact topology, resources, TLS, process isolation |

## TSE-01 Backend Framework
**Known evaluated choices:** NestJS + TypeScript, Fastify + TypeScript, Java + Spring Boot (+ Spring Modulith). Initial discussion considered NestJS; Owner eventually selected Spring Boot/Spring Modulith, so do not preserve NestJS as final choice. Spring Modulith module-boundary verification and Spring ecosystem are important learning/testing drivers. Tradeoff: extra Java/JVM operational burden and learning curve compared with sharing TypeScript full stack.
**Deferred:** Java/Spring versions, build tool, packaging and module verification details.

## TSE-02 Persistence
**Decision:** PostgreSQL relational durable store and transactional consistency support. Candidate Spring Data JPA/Hibernate for ordinary persistence; explicit SQL/JDBC permitted/preferred where locking, atomic leases, fencing, outbox and concurrency semantics demand precision.
**Tradeoff:** ORM productivity vs SQL control and transaction correctness.
**Deferred:** Schema, migrations, exact ORM/SQL division, connection/resource limits.
**Missing:** Exact original alternative database evaluations; do not invent detailed comparisons.

## TSE-03 Durable Job Processing
**Decision:** PostgreSQL-backed durable scheduling/execution and handoff; keep authoritative workflow state/ledger in PostgreSQL rather than introducing Redis/BullMQ by default.
**Known evaluated choices:** db-scheduler candidate vs JobRunr OSS alternative; Explicit PostgreSQL Outbox direction vs Spring Modulith Event Publication Registry alternative requiring verification. PostgreSQL jobs trade easier single-datastore operation for careful lock/query behavior and throughput limits.
**Deferred:** Actual library selection, transaction isolation, dispatcher, polling strategy, retries, schema. Redis is optional only with approved use case.

## TSE-04 Local AI Inference
**Decision:** Use replaceable Inference Port and local-only model execution for source code. Ollama first PoC candidate; llama.cpp comparison; vLLM not a required M01 choice. Protect GitLab credentials and untrusted source/prompt boundaries.
**Tradeoffs:** Local privacy/control vs hardware latency, context capacity, quality and operations. Ollama simplifies PoC; llama.cpp can offer lower-level runtime control. No benchmark has established a winning model.
**Deferred:** Model, quantization, VRAM usage, batching/context budget, GPU container support.

## TSE-05 Frontend
**Decision:** Next.js App Router + TypeScript, consuming Spring Boot business API. BFF handles server-side auth/session; do not duplicate domain rules in Next.js.
**Tradeoff:** SSR/BFF integration and clear UX vs second server runtime and session/caching complexity.
**Deferred:** UI components, client state, server-component caching boundaries and exact BFF library.
**Missing:** Full original frontend-alternatives comparison.

## TSE-06A Keycloak
**Decision:** Self-hosted Keycloak owns identity, accounts/credentials, authentication and IdP sessions. ASEP owns business authorization and Owner-managed provisioning integration; no public registration. Stronger enterprise OIDC learning value at the cost of a significant extra operational service.
**Deferred:** Exact Keycloak version, realms/clients, role mapping, provisioning API credential, TLS/hostname.

## TSE-06B OIDC/BFF
**Decision:** OIDC Authorization Code with PKCE S256; Next.js confidential-client BFF; protected server-side access/refresh token storage; opaque secure HTTP-only browser session cookie; Spring Boot Resource Server JWT validation (signature/JWKS, issuer, audience, expiry) plus application authorization. BFF forwards access token, not ID token. Design considers CSRF, nonce/state, logout/revocation.
**Deferred:** Auth library, session store/TTL, refresh concurrency/revocation, roles and Keycloak reverse-proxy behavior. These security properties are design commitments, not runtime verification.

## TSE-06C Deployment & Supporting Technologies — Revised 2026-10-18
**Considered:**
- A. Host-native services: simple container/gpu integration but weaker reproducibility.
- B. Hybrid Compose — initial preferred direction: containerize Next.js, Spring Boot API/Worker, Keycloak, PostgreSQL; Ollama host-native first PoC candidate.
- C. Fully containerized incl. GPU inference: reproducible but GPU/runtime network validation and resource footprint.
**Approved Revised direction:** single Ubuntu host; Compose initial development/runtime candidate; K3s/Kubernetes and Jenkins planned DevOps/Platform learning targets; Rancher optional lab; Redis requires approved use case and performance/security justification. No change to ADR-001/002 or PostgreSQL-backed execution/outbox.
**Deferred:** Port maps, TLS/Keycloak issuer, memory budgets, Keycloak vs ASEP DB ownership implementation, credential isolation and actual network egress, readiness/backup/restore, GPU passthrough, roadmap allocation.

## Approval provenance and limits
TSE-01..TSE-06C approvals were accumulated during M00-03; Owner explicitly approved TSE-06C Revised on 2026-10-18. Final System Architecture Baseline signed off 2026-10-18 with all TSEs at their respective approval scopes. No technology PoC, exact version or infrastructure runtime verification is implied.
