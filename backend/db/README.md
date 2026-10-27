# VN-T13 private Code Delivery ledger

This is an explicitly constructed synthetic Java capability, with a deterministic
in-memory `FakeGitLab` and a real Java-owned PostgreSQL ledger. It has no Spring
bean, endpoint, production datasource, token handling, live GitLab transport or
agent-facing write capability. Missing trusted checks default to denial. Do not
wire it to real data/operations by treating synthetic approvals as runtime policy.

The approved component boundaries and execution-contract §5 are preserved. There
was no existing Java persistence/migration implementation or M01 Publication
ledger in source to extend. These new migrations create only `code_delivery`;
Go's `orchestration` migrations/code and the M01 Publication design are untouched.
No dependency was copied from the VN-T12 feature branch. VN-T12 was already merged
in the requested origin/main base, `dfc6af4a576fdb2ff64f152bf8f60b2a400c5125`.

## Reproduction

Provision Java 21+, the repository Maven wrapper dependencies, Docker, Python 3,
and the existing `postgres:17-alpine` and `flyway/flyway:11` images. The script
requires existing images; CI provisions them separately before invoking it.

From `backend/`:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
ASEP_VN_T13_DISPOSABLE_DB=true bash scripts/ci_verify_delivery.sh
```

With an already provisioned Maven cache, use `--offline` for Maven and
`ASEP_VN_T13_MAVEN_OFFLINE=true` for the script. PostgreSQL JDBC is test-scoped,
managed by Spring Boot (42.7.13); no production datasource/driver is activated.
Regular `clean verify` explicitly skips the opt-in PostgreSQL class. The required
CI fixture run executes it without skips, checks discovery and repeats the
concurrent exact-intent test five times.

The runner creates a fresh loopback PostgreSQL instance, restricted migration and
runtime roles, and an admin-owned read-only nonce/database/server-start proof.
Tests validate that proof before making changes. It migrates V1/V2 twice using
Flyway checksum validation, then verifies schema history. It never uses Compose or
a configured development database. Generated fixture passwords stay in a private
directory/environment; only the trusted Java test harness receives its own runtime
DB identity. No agent/sandbox runs or receives it. Owned process groups have
deadlines, and cleanup removes only recorded container IDs with this run's exact
ownership label. Runtime cannot modify identity/hash, callback bodies, migration
history, schema objects or another owner's schema.

## Identity, ledger and transitions

The synthetic v1 intent binds operation/run/attempt IDs, expected Go run version,
actor/policy ID+epoch, Jira stable IDs and snapshot digest, repository/base,
deterministic source branch, target/head, candidate commit/tree/manifest, seal
receipt and independent verification receipt/profile, and safe-CI profile digest.
For this slice, Git object IDs are SHA-1, the enrolled target is `main`, identity
labels are synthetic and source branch is `asep/delivery/<operation UUID>`.
There is no caller-provided title/body/URL field.

`bodyHash()` hashes the fixed ordered fields using four-byte big-endian UTF-8
length prefixes and the `asep-code-delivery-fake-v1` domain tag. JSON is a bounded
storage/transport representation, not its canonicalization. Unknown/duplicate JSON
properties and trailing documents fail closed. Exact operation/body replay reads
the original ledger. Changed payload or a replacement operation for the same
run/repository conflicts. Admission handles competing unique indexes before
comparing the complete stored intent.

V1 creates operations and immutable callback content. V2 grants runtime only
SELECT/INSERT and column-restricted updates, never DDL, DELETE, identity rewrite,
migration ownership or cross-owner access. The ledger stores only identities,
digests, step outcomes, remote MR IID, revision and a bounded reason enum. No Jira
text, candidate source, rendered arbitrary content or credential is persisted.

Push and Draft-MR each have PLANNED, IN_PROGRESS, CONFIRMED, UNKNOWN, BLOCKED and
MANUAL_RECONCILIATION_REQUIRED states. MR work requires a confirmed exact branch.
Only the CAS winner of a committed PLANNED→IN_PROGRESS transition may send; network
simulation happens outside DB transactions. A crash leaves IN_PROGRESS for
read-based reconciliation. UNKNOWN/IN_PROGRESS with no proof never authorizes a
blind retry. The fake deliberately offers no remote idempotency key guarantee.

Before **each** mutation (and again after the durable claim), mandatory trusted
ports independently supply current authorization and committed Go-intent binding,
policy/source tuple, sealed candidate identity after writer termination,
controller-issued verification for that exact manifest/run/attempt/profile,
observed target and explicit synthetic safe-CI profile. The synthetic verification
profile requires all discovered tests to run, none skipped/failed/timed out.
Unavailable or contradictory evidence blocks. The fake target head and, for MR,
confirmed remote branch ownership/head/hash are independently checked as well.
The production trusted issuers/authentication remain unwired; incoming agent
fields cannot substitute for them.

The controlled MR renderer accepts only validated UUIDs/digests and a fixed Draft
template. Extra intent fields, arbitrary body text and Quick Actions are rejected.
The fake also checks the exact rendered template before creation. No merge,
approve, force-push, overwrite, delete or comment capability exists.

## Reconciliation and callbacks

- RF-12: stale Jira/base/target/policy/candidate/manifest, bad authorization,
  verification or CI blocks before a send. A detected post-send change records a
  residual race and preserves actual confirmed/unknown effects. It cannot grant
  new workflow completion authority.
- RF-13: lost push reply leaves UNKNOWN. Recovery requires exact repository,
  branch, head, operation owner and intent hash. Collision blocks; never overwrite.
- RF-14: lost MR reply recovers only one exact project/source/target/head/Draft/
  owner/hash match. Multiple candidates remain UNKNOWN; no second MR is created.
- RF-15: branch CONFIRMED/MR UNKNOWN or BLOCKED is partial, never Draft-complete,
  and does not trigger compensating deletion.
- RF-16: removed write access denies every new mutation. Exact historical effects
  may still be confirmed through independently permitted reads. If reads are
  unavailable, unresolved steps become MANUAL_RECONCILIATION_REQUIRED. No token
  exists in this adapter/ledger to retain or reuse.

Each local ledger transition commits an immutable callback snapshot in the same
transaction. Its stable UUID derives from operation+revision, and its versioned
body hash binds operation, run, expected Go version, original intent hash, step
states, MR IID and residual-race flag. Duplicate/reordered exact acknowledgements
are idempotent and never rewrite operation state. A changed acknowledgement
conflicts. A mandatory trusted port gates ACKs. These are **outbound Java
observations**, not accepted Go workflow transitions. Current Go source has
synthetic stage callbacks only, not a production Code Delivery receiver; future
transport must authenticate and bind them before Go can apply any transition.
No new unauthenticated callback endpoint or Go state writer was added.

Executor reconstruction and fresh PostgreSQL connections verify durable recovery;
the fake remote object represents remote state surviving the executor. Tests also
simulate the persisted pre-send and post-effect crash windows. This is not a
cross-process production auth test, real GitLab permission/CI evidence, physical
PostgreSQL outage test or remote exactly-once claim. Runtime policy, real sealing/
verification issuers, real transport, credential lifecycle and production retention
remain separate gates. F01 remains OPEN.
