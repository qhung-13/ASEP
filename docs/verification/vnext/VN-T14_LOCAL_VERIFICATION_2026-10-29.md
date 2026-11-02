# VN-T14 local verification — 2026-10-29

This records implementation evidence, not ticket acceptance, a roadmap update or
approval to deploy. Baseline: `a48f53d9ca6d75745bedbdc4d0676a9b86efb716` on
`feat/vn-t14-authorized-gitlab-test-project-draft-mr-delivery`. Contrary to the
prompt's historical snapshot, the V5 foundation was already committed at that
baseline. The only initial uncommitted files were seven unrelated VN-T11 specs.
The user master prompt explicitly authorized focused local commits; no push or
merge was authorized/performed. Existing PR #11 remains the review target.

Local checkpoints: `fac6523` (grant transaction/privilege regressions), `272cc7f`
(restricted operator capability), followed by the Go authority/Java reader
checkpoint containing this report. No existing migration was rewritten.

## Commands and actual results

Working directory for Maven commands: `backend/`. All used the verified Java 21
installation at `/usr/lib/jvm/java-21-openjdk-amd64`.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw --offline --batch-mode --no-transfer-progress -Dtest=RestrictedOperatorRuntimeTest,TestProjectOwnerGrantVerifierTest,TestProjectTrustedEvidenceCompositeTest test
```

Final focused run: **40 tests, 0 failures/errors/skips, exit 0**.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw --offline --batch-mode --no-transfer-progress -Dtest=GoDeliveryAuthoritySourceTest,TestProjectCurrentPolicyVerifierTest,TestProjectTrustedEvidenceCompositeTest,TestProjectDeliveryGateTest test
```

Focused authority/real loopback mTLS run: **87 tests, 0 failures/errors/skips,
exit 0**. A shared Java/Go codec golden assertion was subsequently added and
passed in the final full regression.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw --offline --batch-mode --no-transfer-progress clean verify
```

Final run: **1,075 tests, 0 failures/errors, 130 fixture skips, BUILD SUCCESS,
exit 0**. The skips are not counted as executed tests. The separate PostgreSQL
harness executes the 129 DB tests; the full Go harness separately compiles/runs
the private Java admission bridge fixture. Earlier operator-only full regression
passed 1,068 tests with the same 130 skips.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ASEP_VN_T13_DISPOSABLE_DB=true ASEP_VN_T13_MAVEN_OFFLINE=true bash scripts/ci_verify_delivery.sh
```

Final run: **114 + 22 = 136 selected tests, 0 failures/errors/skips, exit 0**
(129 PostgreSQL + 7 contract unit tests). Flyway V1–V6 migrate/repeat/validate
passed. Five VN-T13 concurrent replay repetitions and three VN-T14 CAS repetitions
passed. Label-owned cleanup passed (run `3da6c9c38de3ea2e4d5bb1923491691a`).
The foundation-only run before the operator additions passed 114 + 14 = 128.

The following commands ran from repository root, using the verified installed
Go toolchain `/home/switer/.local/opt/go1.27.3/bin/go`:

```sh
PATH=/home/switer/.local/opt/go1.27.3/bin:$PATH go -C orchestrator test -race ./internal/deliveryapi
PATH=/home/switer/.local/opt/go1.27.3/bin:/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T11_HARNESS_MODE=delivery-authority ASEP_VN_T12_MAVEN_OFFLINE=true bash orchestrator/scripts/ci_verify.sh
PATH=/home/switer/.local/opt/go1.27.3/bin:/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T12_MAVEN_OFFLINE=true bash orchestrator/scripts/ci_verify.sh
```

All final runs **exit 0**. The focused owned fixture executes nine new top-level
PostgreSQL tests and 20 subcases, with no skips; it also runs Go vet and unit tests
with the race detector. The full harness passes populated V1–V11 compatibility,
all existing PostgreSQL regressions, actual PostgreSQL restart/re-enrollment,
vet/unit/race checks and owned cleanup. Its verbose log contains **132 top-level
PASS result entries, 0 FAIL, 0 SKIP**, including populated-upgrade/repeated baseline
executions; this is not a claim of 132 distinct test functions. The final focused
run strengthened ACL error-code assertions and isolated policy expiry from epoch
mismatch. No source behavior changed after the final full Go harness.

```sh
bash -n orchestrator/scripts/ci_verify.sh backend/scripts/owner-approval backend/scripts/ci_verify_delivery.sh
git diff --check
git diff --cached --check
graphify extract . --code-only --no-cluster
```

Passed. Graph extraction used local ASTs only; generated artifacts remain ignored.

The packaged CLI was invoked from this checkout both with and without a terminal:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64/bin/java -Dloader.main=io.github.qhung13.asep.codedelivery.OwnerApprovalCommand -cp backend/target/asep-backend-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher approve /tmp/not-an-authorized-request.json 60
```

Both **exit 1, expected denial**, with a generic error and no grant. This proves
only denial in this environment, not a successful independent human approval.

Development failures were resolved before the final passes: the initial 40-test
operator run and a nine-test diagnostic rerun failed on the valid evidence
fixture's inherited group-write permissions (the guard correctly denied it);
the fixture now uses explicit private permissions. A policy-focused run initially
failed compilation because one gate fixture still used the old snapshot
constructor; it was updated. The first new Go owned run failed in its protected
regression phase; private diagnostic cleanup prevented retaining the exact
failure detail. Subsequent diagnostic-enabled and final runs passed. Some patch
commands initially used repository-relative paths from a component directory,
and one Go command ran outside its module; those commands failed and were
corrected. No failed command is counted as a pass.

Local diagnostic logs are under `/tmp/asep-vnt14-master-*.log`; no credential is
printed in this report. Primary final logs: `final-java-verify2`,
`final-java-postgres`, `go-full-final`, `go-expiry-acl-final` (with that prefix and
`.log` suffix).

## Security review and limits

| Finding | Location/impact | Resolution or remaining boundary |
|---|---|---|
| P1: caller actor labels are not human identity | Grant issuance/audit could attribute a raw caller string | Java V6 derives identity/time from DB session; installed CLI independently checks kernel UID, protected artifact/config and issuer principal. Real host isolation remains BLOCKED |
| P1: run/version policy evidence did not bind exact candidate operation | `TestProjectCurrentPolicyVerifier.Snapshot` could be reused across candidate identities | Added operation ID, canonical full-request hash and expiry; negative tests and shared Go/Java golden codec pass |
| P2: delivery cancellation incompatible with legacy audit constraint | Original constraint allowed only earlier synthetic stage cancellation | Forward Go V11 preserves old predicate and adds typed DELIVERY_CANCEL; stable replay retains original audit/outbox/fencing |
| P2: expiry could elapse while acquiring locks or reading a remote authority | Old freshness must not be refreshed by response completion | Go captures read start, checks DB time after waits and returns minimum validity deadline; Java rejects missing/expired/stale/future evidence; controlled lock and TLS tests pass |
| Privileged evidence roles | Row-share locking needs column UPDATE rights | Grant immutable keys only; guards reject actual runtime updates. Real PostgreSQL error-code and SET ROLE tests pass; runtime cannot issue/revoke protected evidence |
| External mutation race | Go DB cannot atomically transact with GitLab | No claim of zero TOCTOU or rollback. Keep existing Java mutation-boundary revalidation, claims, UNKNOWN/race observation and reconciliation requirements |

No live authority, writer or successful end-to-end production trust chain was
activated. Review did not establish real isolated operator/controller identities,
Safe-CI, a usable GitLab credential or real remote delivery. These remain blocking
acceptance conditions, not resolved security findings.

## Acceptance preparation and merge readiness

See [deployment/evidence proposal and blocker matrix](DELIVERY_AUTHORITY_IMPLEMENTATION.md).
No actual sealed candidate/independent receipt was issued for live use, so no
candidate SHA is proposed for approval yet. The old public main SHA is only an
observation and must be revalidated. Do not implement/activate the GitLab writer
until Part E's security prerequisites are independently established.

For later QA, require: isolated human grant issue/revoke/expiry; real Go review
progression and cancellation/epoch changes; authenticated service identity and
failure cases; independently sealed exact probe and verifier profile; complete
fresh Safe-CI evidence; scoped credential lifecycle; exact-operation Owner approval;
observed branch push and Draft MR; response-loss/drift/revocation cases retaining
actual effects; replay/reconciliation without duplicate sends, deletion or main
mutation. None of the live cases is represented as passed.

Proposed future PR description: Add restricted local operator grants and Go-owned
exact-candidate delivery authorization with cancellation/replay, an authenticated
read contract and strict Java authority validation. Owned PostgreSQL, loopback TLS
and regression tests pass; protected deployment, actual evidence issuers, Safe-CI,
credentials and GitLab write/acceptance remain blocked. **Not merge ready as a
completed VN-T14 feature.** No remote PR edit was performed.

## Complete change manifest since the inspected baseline

```text
backend/db/migrations/V6__bind_grant_audit_to_authenticated_principal.sql
backend/pom.xml
backend/scripts/ci_verify_delivery.sh
backend/scripts/owner-approval
backend/src/main/java/io/github/qhung13/asep/codedelivery/GoDeliveryAuthoritySource.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/JdbcOwnerGrantSource.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/OwnerApprovalCommand.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/OwnerGrantAuthority.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/ProtectedDeliveryEvidence.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/ProtectedOperatorFiles.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/RestrictedOperatorRuntime.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/TestProjectCurrentPolicyVerifier.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/GoDeliveryAuthoritySourceTest.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/OwnerGrantAuthorityPostgresTest.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/RestrictedOperatorRuntimeTest.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/TestProjectCurrentPolicyVerifierTest.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/TestProjectDeliveryGateTest.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/TestProjectTrustedEvidenceCompositeTest.java
docs/verification/vnext/DELIVERY_AUTHORITY_IMPLEMENTATION.md
docs/verification/vnext/VN-T14_LOCAL_VERIFICATION_2026-10-29.md
orchestrator/db/migrations/V11__create_delivery_authority.sql
orchestrator/internal/deliveryapi/server.go
orchestrator/internal/deliveryapi/server_test.go
orchestrator/internal/deliveryapi/tls_test.go
orchestrator/internal/workflow/delivery_authority.go
orchestrator/internal/workflow/delivery_authority_integration_test.go
orchestrator/internal/workflow/delivery_authority_test.go
orchestrator/scripts/ci_verify.sh
```

The exact patch is reproducible with `git diff a48f53d HEAD --binary`.
The seven original specs and all previously applied migrations were
verified byte-for-byte against the initial snapshot. No other pre-existing
tracked file outside the manifest changed. Final staging/working tracked diff
is empty after the authorized local checkpoints; only original specs remain
untracked. PR #11 was re-read as OPEN/unmerged at remote SHA
`eb5ab52fa932c626e5897e0024d84f02c9b13870`; no remote write was made.
