# ASEP local PostgreSQL foundation

This Compose project is **development-only** (`asep`). It does not connect to
real Jira/GitLab, modify the Java backend, or grant any sandbox DB access.

- PostgreSQL: `postgres:17-alpine`, database `asep`, bound **only** to
  `127.0.0.1:55432` on the host.
- One-shot schema migrator: `flyway/flyway:11`, runs as **trusted** container.
- Schema `orchestration` is owned by `asep_orch_migrator`.
- Future Go runtime role `asep_orch_runtime` has DML rights only on its own
  workflow tables, not CREATE/ALTER, or Flyway history write rights.
- Local secrets are generated outside Git in
  `~/.local/share/asep/dev/secrets/` with owner-only file permissions.
- A named Docker volume holds database state across container restarts.

Start DB: `docker compose -f orchestrator/compose.yaml up -d --wait postgres`
Migrate: `docker compose -f orchestrator/compose.yaml run --rm flyway`
Stop DB (preserve state): `docker compose -f orchestrator/compose.yaml down`
**Danger: `down -v` destroys the local prototype database. Do not use casually.**

The initial V1/V2 migrations are a **prototype schema proposal** under VN-T10;
review constraints/grants, transactions and persistence with PostgreSQL
integration tests before declaring the workflow implementation complete.
Image tags are major-version scoped for this local bootstrap; pin audited image
digests before promoting to a reproducible deployment profile.


## VN-T10 stage 1: trusted admission and lease claim

`internal/workflow` contains a deliberately small Go repository. It uses the
`asep_orch_runtime` role, so it cannot migrate or delete schema/data. There are
no externally reachable HTTP endpoints yet: `Admit` is restricted to trusted
synthetic test callers until Java->Go service authentication/authorization is
implemented. **Caller-provided hashes are not independent authorization proofs.**

Current stage-1 guarantees tested against PostgreSQL:
- A transaction creates the run, initial stage attempt, delivery, transition and
  outbox message together; command-ID replay returns the same receipt.
- The partial unique index rejects a second active run for the same subject.
- `FOR UPDATE SKIP LOCKED` plus a shared run-row lock protects atomic claims.
- Claim ownership and heartbeat use DB time and a monotonically increasing
  lease epoch; expired leases cannot be renewed even before a reclaim.
- Infrastructure redelivery increments delivery_count/lease_epoch, not the
  semantic attempt number or correction budget.
- Database state survives reopening a separate Go connection pool.

Not yet implemented: an authenticated HTTP boundary, policy/evidence validation,
actual candidate stage transition/next-stage task commit, cancellation and
reconciliation. Those must NOT be inferred from the stage-1 PASS result.
Integration test fixtures are namespaced and removed by a separate trusted
harness; `go test -tags integration` must be run only with a locally approved
synthetic test database and `ASEP_ORCHESTRATOR_DATABASE_URL` + `ASEP_VN_T10_TEST_PREFIX` environment configuration.



## VN-T10 stage 2: trusted investigation transition and atomic outbox

A deliberately **synthetic-only, unexported** `advanceInvestigation` method
moves `INVESTIGATING` -> `PLANNING` with one Go-owned PostgreSQL transaction.
It checks run version, policy epoch, ticket/source fingerprint and the CURRENT
unexpired task lease under row locks. It marks the old task/attempt complete,
increments run version and creates the next attempt/task, audited transition
and outbox record atomically; a late SQL constraint error rolls everything back.
Successful ADVANCE cannot debit the semantic correction budget.

The method is NOT exposed as an HTTP endpoint and currently accepts a
namespaced synthetic evidence reference, not independently verified real
Investigation evidence. Before real workflow use, add authenticated service
boundary and controller-issued evidence validation. Cancellation, policy
revocation races, evidence receipts, durable dedupe of duplicate result IDs,
outbox delivery/inbox ACK and later-stage transitions remain unimplemented.

The outbox payload digest hashes the typed Go JSON serialization before the
payload is stored as JSONB. **Do not assume** `SHA256(payload::text)` equals
this digest: cross-language canonicalization and consumer verification require
an explicit versioned contract before dispatch.

Integration tests cover: atomic successor/outbox, forged/stale lease/policy/
revision rejection, expiry-before-reclaim, competing completions, late SQL
error rollback, and reconnect persistence. The separate maintenance connection
cleans ONLY namespaced synthetic records, never using Go runtime DELETE.



## VN-T10 stage 3: synthetic-only local HTTP boundary and process restart

The Go service currently offers a **development-only** HTTP adapter. It binds
exclusively to `127.0.0.1`, uses a private, high-entropy token file outside Git,
accepts only one explicitly configured `synthetic:vn-t10-it-<uuid>` subject
namespace, and returns sanitized workflow status data. `GET /healthz` is a
loopback-only liveness endpoint without auth; all `/v1` routes require the
prototype bearer token. `POST /v1/tasks/claim` and `/heartbeat` do not accept
workflow transition/evidence authority. No `/result`, `/advance` or Jira/GitLab
endpoint exists.

This is NOT a production machine-auth design. The token never belongs in agent
or sandbox environments, and the local service must not be exposed externally.
Real service identity, policy authorization, independent evidence binding,
per-role task capabilities and authenticated result submission are still OPEN.

`go run ./cmd/asep-orchestrator` retains the initial harmless bootstrap message;
`go run ./cmd/asep-orchestrator serve` needs explicit loopback address,
`ASEP_ORCHESTRATOR_DATABASE_URL` (runtime role only), a private
`ASEP_ORCHESTRATOR_DEV_TOKEN_FILE`, and an allowed synthetic subject prefix.
Credentials are never stored in the repository or printed in Go request logs.

Integration tests launch the actual Go binary, issue authenticated requests,
kill the process via SIGKILL, start a new process and verify the same run and
lease/budget persisted in PostgreSQL. A regression also proves heartbeat binds
its attempt ID and stage, not only task/claimant/epoch. No timer/watchdog or
VN-T11 fault-injection recovery is asserted.



## VN-T10 admission hardening / PR verification (CR-01..03)

- **V3 is forward-only:** replace the active-run unique partial index on the
  canonical `(jira_site_id, jira_project_id, jira_issue_id, gitlab_project_id)`
  identity. This prevents two active runs for the same Jira issue and enrolled
  GitLab repository even when `subject_key` differs. `subject_key` remains the
  synthetic-scoping/display label; do **not** treat it as durable authority.
- The Go admission method computes `command_payload_sha256` from a fixed-order,
  JSON-encoded **version-1** semantic payload. `command_id` is an idempotency
  key outside the payload. `PayloadSHA256` (if present) is an optional caller
  assertion; mismatches fail closed. Exactly matching commands replay a saved
  receipt; changed actual body conflicts even when the caller repeats a digest.
- The canonical Go serialization is a **prototype contract**, not a finalized
  interoperable Java/Go wire format. Cross-service normalization, caller
  identity/authentication and authorization remain gates before real admission.
- On an existing development database: `docker compose -f orchestrator/compose.yaml
  run --rm flyway` applies **only the new V3 migration** if V1/V2 are present;
  existing Flyway V1/V2 files must never be edited after application. A V3
  duplicate-identity index fails migration if the existing active data violate
  the invariant; resolve with a reviewed business policy, never blind deletes.
- Fresh-repository CI runs `orchestrator/scripts/ci_verify.sh` from GitHub
  Actions (also runnable locally with an explicit disposable-DB opt-in). It
  generates disposable PostgreSQL credentials, provisions its
  **own new database/schema/roles**, migrates V1-V3 using Flyway and runs
  `go test -race -tags=integration`. A cleanup trap removes the disposable
  container even if verification fails. It refuses to run against the local
  persistent `asep` database; the developer's local database is NOT used in CI.
- Local Go integration tests continue to require an explicitly approved
  synthetic fixture database and a trusted, namespaced cleanup harness.
- **Not yet implemented:** production Java authorization, real trusted evidence
  receipts, external writes, outbox delivery, complete workflow states and
  VN-T11 crash/fault-injection suite. V3 does not change audit-table permissions
  (medium finding VN10-CR-04 is tracked separately).

## VN-T10 CR-04: append-only transition audit at runtime role level

Flyway V4 revokes `UPDATE` on `orchestration.workflow_transitions` from
`asep_orch_runtime`. The runtime retains `SELECT` and `INSERT`, while `DELETE`
remains ungranted. The table is append-only **for this restricted role**;
this is not a claim of tamper-proof storage against database administrators,
migrator privileges or a compromised trusted writer.

The tagged integration test `TestRuntimeCannotRewriteTransitionAudit` verifies
actual SQLSTATE `42501` for an attempted audit UPDATE. Disposable CI creates a
fresh PostgreSQL database, applies Flyway V1-V4 and executes the full tagged
race suite. Existing migrations V1-V3 are unchanged.
