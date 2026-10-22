# ADR-VN-04 — PostgreSQL Domain Ownership and Internal REST Contracts

**Date:** 2026-10-22
**Status:** ENGINEERING PROPOSAL for Owner-selected vNext direction (2026-10-22); grants, internal authentication and contract correctness NOT APPROVED/VERIFIED.
**Related:** TSE-02/03 PostgreSQL direction, ADR-002 leases/ledger, MD-05/06/08/09 module ownership.

## Context

ASEP vNext has Application/Java, Go Orchestration and Python Agent Execution. Multiple runtimes do not imply multiple database engines, but shared PostgreSQL is unsafe if every service reads/writes any table. HTTP calls between owners cannot participate in one atomic transaction with GitLab.

## Documented vNext design direction

- **One PostgreSQL instance initially**, a domain-owned `asep` database with separate schemas/DB roles for Application, Orchestration and privileged Integration Operations; a distinct Keycloak database on the same instance may be used with independent identity credentials.
- Python Agent Runtime and lower-trust sandbox have **no direct business-database credentials**.
- Runtime roles have narrowly scoped grants and safe `search_path`; migration roles are separate. Versioned migrations require sequencing compatible with Java/Go and in-flight task payloads. No cross-domain writes or reliance on cross-schema foreign keys as the integration interface.
- Use versioned, authenticated **REST/JSON internal contracts** initially; use OpenAPI/JSON Schema where useful. Not a requirement to introduce gRPC/Redis/Kafka.
- Python worker uses **pull/claim/heartbeat/submit** APIs; Go performs the durable PostgreSQL claim and fencing checks.
- Client-initiated commands use stable idempotency IDs and durable command admission. Outbox/inbox is used where atomic local state + reliable cross-process delivery is necessary.
- The Java Trusted Action Executor persists independently-authorized Code Delivery **branch-push and Draft MR-creation steps separately**, reconciles each `UNKNOWN` state and checks CI-trigger safety; the current M01 comment Publication ledger remains separate.

## Data ownership

| Domain | Durable owner | Main data |
|---|---|---|
| Application business | Java | Integrations, policy configurations, authorized read models and business user permissions |
| Orchestration | Go | Workflow runs, stage attempts, tasks, lease epochs, transition/evidence references |
| External Action operations | Java Trusted Executor | Code Delivery operation IDs, status, remote IDs/reconciliation, separately from M01 comment publication |
| Identity | Keycloak | IdP identities and sessions |
| Temporary agent source/artifacts | Scoped workspace/storage | Candidate patch/digest; minimize durable raw source and keep lifecycle explicit |

**Cross-owner IDs** are stable references, not permission to bypass another domain's API. A single PostgreSQL server is still a shared availability boundary; no claim of fault independence or effortless scale-up.

## Failure handling

- HTTP timeout after commit: command retried with same ID returns existing logical outcome, not a second workflow.
- Outbox/event duplicate: receiver dedupes by message/operation ID; no exactly-once delivery assertion.
- Python late result: Go fences ownership epoch and candidate revision at durable commit.
- Revoked integration/policy: new privileged actions are rejected; pending activity is cancelled or escalated safely. If write credentials are removed while a remote result remains UNKNOWN, keep only authorized minimal unresolved metadata and require manual reconciliation when no read capability is available. Never secretly retain revoked write credentials.
- Remote GitLab ambiguous write: Trusted Executor queries remote before retry; Go records outcome callback idempotently.
- Inter-service authentication is required independently of Docker networking; exact mechanism (scoped internal credentials vs Keycloak service accounts) remains OPEN.

## Alternatives

| Alternative | Main advantage | Why not primary |
|---|---|---|
| PostgreSQL schema + role ownership | Low overhead and cohesive durable transactions within owners | Requires discipline on grants/migrations and shared-resource monitoring |
| Separate PostgreSQL instances/databases per component | Stronger operational resource isolation | Unnecessary overhead without measured capacity/trust benefit at initial single-host scale |
| PostgreSQL + MariaDB split | Different engine options | Duplicated operational/migration/backup complexity; no requirement supports heterogeneity |
| gRPC/event broker everywhere | High-throughput or streaming options | No evidence of initial need; adds tooling and delivery semantics |
| Direct cross-schema SQL as the integration API | Cheap short-term reads/writes | Breaks ownership, testing, future migration and authorization boundaries |

## Execution contract boundary

See the [canonical execution contracts and gates](../../vnext/EXECUTION_CONTRACTS_AND_GATES.md) for admission/body-hash dedupe, atomic Go transaction+outbox, authenticated evidence, exact revision tuple, Java Code Delivery inbox and per-step ledger, policy revocation and eventual consistency. The HTTP transport is an engineering candidate, not proof that cross-service identity is configured.

## Required verification (OPEN)

- PostgreSQL schema grants and migrations: cross-owner writes denied; no superuser runtime.
- Command and outbox crash-window idempotency and duplicate delivery.
- Service auth, token expiry/rotation/revocation, unauthorized/Viewer path rejection.
- Error/timeouts and ownership/fencing correctness across Java-Go-Python.
- Credential lifecycle under integration removal; safe Code Delivery reconciliation.
- Schema migration/rollback and disaster recovery feasibility.

## Consequences

The integration surface is clear and debuggable, but adds API/versioning, eventual consistency and identity handling. No full cross-component transaction can enclose remote Jira/GitLab writes. Individual application transactions remain local to their owning domain.
