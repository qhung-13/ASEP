# VN-T14 local preflight and live-delivery prerequisites

Status: PARTIAL — local read-only inspection, authorization gate, durable VN-T14 PostgreSQL ledger and synthetic coordinator verified; live GitLab mutation remains BLOCKED.
These are implementation notes, not an authorization or newly approved policy.

Base: `05ab5155c5a65ee8c78527d9310a67d315d021e6` (updated origin/main).
Branch: `feat/vn-t14-authorized-gitlab-test-project-draft-mr-delivery`.
Unrelated VN-T11 specs are preserved. No development-branch push is authorized.

## Implemented locally

`GitLabRestTestProjectReadAdapter` uses the existing trusted GET-only client pattern
and fixed numeric project selection to inspect project metadata/effective role,
exact target branch/head, protected-branch count, runner count, project-variable
count, environment count and pipelines bound to the observed target SHA.

HTTP errors, redirects, malformed/oversized bodies, missing metadata and incomplete
pagination produce explicit evidence gaps. It never follows response links,
returns variable keys/values, persists response bodies or infers CI success from
MR creation. Pipeline ID/SHA/status/source and observation time remain separate
facts. Caller-supplied URLs are not accepted as branch/project selections.

The adapter must receive a client from `GitLabReadHttpClientFactory` in an explicitly
trusted read-only host process. The explicit `VnT14ReadOnlyPreflight` trusted Java harness reads only an Owner-selected
private POSIX file, rejects unsafe permissions, links and header content, suppresses
HTTP/library logging and retains no raw response or credential in its report. The
credential stays in the one short-lived Java process; no environment lookup or child
process handoff is used. No Spring bean, scheduled action or mutation endpoint is
installed. Default tests replace the transport with local HTTP fixtures.

`TestProjectDeliveryGate` separately requires exact Owner-enrolled test scope,
current authorization/policy, inspected account/credential mechanism, credential
isolation/lifecycle, exact independently sealed/verified candidate, safe CI including
inherited settings, controlled rendering/fault-window authorization and trusted live
executor wiring. It also enforces complete current observations and exact project/
target/head binding. Observation-age policy must be supplied explicitly; the unit
profile is not a production policy.

Its default verifier grants nothing. VN-T13 `DeliveryChecks`, synthetic test receipts,
empty project-variable lists and a known branch head do not establish live authority.
There is no configured live verifier or live writer, and this gate cannot itself
create either. Test verifier fixtures are not actual authorization/evidence.

## What is still missing

Before any real push or MR create, record and independently verify:

1. The Owner has explicitly enrolled `https://gitlab.com/qhung-13/asep-m01-sandbox`
   (87786140), source `test/vn-t14/<operation-id>`, target `main` for Draft MR only.
   Main push/merge/approval/force-push are forbidden. Bind that grant and controlled
   fault windows to a concrete live operation before any mutation.
2. A dedicated Owner-supplied project access token is selected: Developer, `api` and
   `write_repository`, expiring 2026-11-04. Its active/unrevoked status, scopes, expiry
   and effective Developer role were confirmed by authenticated GET. The account
   plan was not visible. Inspect remaining branch/settings restrictions; do not
   elevate this credential. Revocation is authorized only for this dedicated token
   in the staged test. Never paste it in chat, URLs, CLI arguments or logs.
3. The supplied credential file passed 0700 directory/0600 regular file, same-owner
   and single-link checks. Only the trusted Java harness read it, in a cleared
   environment with no children and HTTP logging suppressed. There is still no
   configured live executor or protected candidate/verifier sandbox. The local file
   is deleted after this blocked attempt; no copy is retained. No server-side
   revocation test was attempted because the delivery/fault sequence never began.
4. Exact target head and sealed candidate commit/tree/manifest, source path/type
   limits, producer termination/seal receipt and independently issued verification
   receipt for the exact immutable candidate. This agent's build/test log is not
   such a delivery receipt.
5. Safe-CI evidence: project/group/inherited/protected variables, runners, pipeline
   rules/includes/compliance policies, environments/deploy permissions and candidate
   CI changes. Missing visibility is UNKNOWN, not proof of absence. No existing
   project setting is changed by the inspector.
6. Controlled title/body and operation identity; durable branch and MR steps;
   non-overwriting exact-candidate transport and independent remote lookup; staged
   lost-response/collision/stale-target/revocation execution and cleanup policy.

The merged VN-T13 executor is explicitly coupled to `FakeGitLab`; its synthetic
intent identity/codec and trusted test ports must not simply be relabeled live.
It has no live Git push, MR HTTP writer, credential-enabled delivery process, protected
artifact/evidence issuer or real enrollment provider. Those must be implemented
and securely wired for the chosen Owner-approved test mechanism. Existing Go
workflow authority and M01 Publication remain separate. Production Keycloak or a
new Go receiver is not invented as an additional prerequisite for a narrowly
Owner-authorized test controller, and no configured authentication is claimed.

## Available read-only evidence

Authenticated read-only inspection at **2026-10-28T01:37:24.918386056Z** used the
Owner-selected file only in the explicit trusted Java process. The compiled-in
project is 87786140; no request method other than GET is supported. A mode-0600
sanitized `/tmp/asep-vnt14-authenticated-preflight.json` records status/counts and
allowlisted facts. No raw body, variable key/value, hook URL, private user details
or credential leaves that process.

| Observation | Actual result | Meaning |
|---|---|---|
| Identity / credential self metadata | HTTP 200; bot, active, not revoked; requested scopes/expiry match | Read capability confirmed, no mutation capability exercised |
| Project identity / role | HTTP 200; exact Owner-selected project; role 30 (Developer); not archived | Owner grant recorded separately from API observation |
| Target `main` | HTTP 200; `cd0b93035954142346b7cba18f29657ee877163b`; protected; credential cannot push | Snapshot only; re-read before any later mutation |
| Main protection | HTTP 200; one rule, push/merge level 40; force-push false | No direct main mutation permitted |
| Instance/shared runners | Project flag true; runner list HTTP 403 | Runner capabilities and isolation unverified |
| Project variables | HTTP 403 | Cannot confirm absence, including protected/inherited secrets |
| Environments | HTTP 200; zero | Does not establish no deployment capability |
| Protected environments / deploy keys / hooks | HTTP 403 each | Deployment/side-effect restrictions unverified |
| Auto DevOps / CI override | Auto DevOps not returned; no custom CI path; `.gitlab-ci.yml` at target HTTP 404 | Owner UI observation retained; no safe-CI or candidate-policy inference |
| Target pipelines | HTTP 200; zero | No CI result or CI PASS claim |
| Namespace/account plan | HTTP 404; UNKNOWN | Do not infer account tier from token availability |

The Owner's UI observations (Auto DevOps off, no project variables or specific
runner, instance runners enabled) are recorded authority/context, not full safe-CI
enrollment. Group/inherited/compliance settings, candidate CI, independently sealed
candidate/verification and trusted live executor remain unestablished. Missing
visibility is a blocking gap; no permission escalation was attempted.

No real branch, commit, Draft MR, pipeline, merge, approval, delete or revocation
was performed. No live operation is pending/UNKNOWN because no mutation was sent.
The required delivery, lost-response/lookup, stale-target, collision and revocation
acceptance cases remain **NOT RUN**. The local synthetic PostgreSQL fixture proves
its existing synthetic contracts, not these remote acceptance cases.

Primary references:

- https://docs.gitlab.com/user/project/settings/project_access_tokens/
- https://docs.gitlab.com/security/tokens/access_token_scopes/
- https://docs.gitlab.com/api/projects/
- https://docs.gitlab.com/api/project_level_variables/
- https://docs.gitlab.com/api/runners/
- https://docs.gitlab.com/api/merge_requests/

## Reproducible local checks

From backend, using Java 21 and the provisioned Maven cache:

```bash
./mvnw --offline --batch-mode --no-transfer-progress \
  -Dtest=OwnerSuppliedCredentialFileTest,VnT14ReadOnlyPreflightTest,GitLabRestTestProjectReadAdapterTest,TestProjectDeliveryGateTest test
./mvnw --offline --batch-mode --no-transfer-progress clean verify
ASEP_VN_T13_DISPOSABLE_DB=true ASEP_VN_T13_MAVEN_OFFLINE=true \
  bash scripts/ci_verify_delivery.sh
```

The last command runs the existing owned disposable PostgreSQL fixture; it creates
no GitLab object and provides no live feasibility evidence. The normal existing
live GitLab smoke test remains opt-in and read-only. No real push, Draft MR,
pipeline creation, merge, approval, Jira mutation or credential revocation is
performed by this local preparation.

## Current attempt and credential cleanup

The authenticated preflight completed successfully as a **read-only inspection**,
not as an accepted enrollment or delivery. The local credential file was removed
and absence checked after the inspection. The dedicated server-side token was
active/unrevoked when read; it was not revoked by this attempt. Local deletion is
not a revocation test. The Owner can revoke that dedicated token in GitLab; a later
attempt must obtain a fresh secure setup rather than assume this file remains.
No credential was read by shell/Python, copied to the repository, supplied to a
verifier/agent child, or put in Git configuration/environment/command arguments.
The harness received only a path, used a cleared environment and suppressed library
logging. Java retains header Strings for the scoped client lifetime; it cannot
promise JVM heap zeroization. The process closed its client and terminated; no
heap dump was enabled. This is a host harness, not a configured sandbox-isolation
or production credential service claim.

Current verification (Java 21, existing offline Maven cache):

- Focused four-class suite: 58 tests, zero failures/errors/skips, BUILD SUCCESS.
- Java `clean verify`: 719 discovered, 664 passed, 55 opt-in skips, BUILD SUCCESS.
  The skips are the 54 real-PostgreSQL cases and the existing opt-in live read smoke.
- Explicit PostgreSQL fixture: 54 database tests plus 7 contract tests passed,
  zero failures/errors/skips; all five concurrent replay repetitions passed.
  Owned container cleanup passed and an independent label-scoped lookup was empty.
  These are real PostgreSQL tests of the synthetic adapter, not live GitLab tests.
- Real GitLab read-only preflight: exit 0; gate BLOCKED; zero mutations.
- All remote push/MR/fault/revocation acceptance tests remain NOT RUN due to the
  prerequisites above. No independent candidate verification receipt is claimed.

## Local continuation after Owner UI context

The subsequent local continuation adds a separate VN-T14 request identity, exact
single-file Git-object/manifest preparation, protected controller-receipt validation,
controlled Draft rendering and a pure read-only reconciliation classifier. There
is still no live transport, configured receipt issuer, versioned durable VN-T14
request storage or live activation. The synthetic VN-T13 executor remains unchanged.
The Owner's additional no-project-runner/variables/deploy-keys/hooks and Auto DevOps
observations remain incomplete context; no authenticated setting was re-read and
the deleted credential was not recreated or recovered.

See [evidence coverage, remaining executor work and proposed credential lifecycle](VN-T14-EVIDENCE-AND-LIFECYCLE.md).
Earlier test counts above describe the previous attempt; current continuation
commands/results are recorded separately. No acceptance/task status was changed.

Current continuation verification: 137 focused cases passed; Java `clean verify`
passed (798 discovered, 743 passed, 55 opt-in skips); the explicit PostgreSQL
fixture passed all 61 cases and five replay repetitions. No live test or credential
operation ran in this continuation. Use Java 21 from `backend`:

```bash
./mvnw --offline --batch-mode --no-transfer-progress -Dtest=TestProjectDeliveryPreparationTest,TestProjectRemoteReconciliationTest,TestProjectDeliveryGateTest,GitLabRestTestProjectReadAdapterTest,OwnerSuppliedCredentialFileTest,VnT14ReadOnlyPreflightTest test
./mvnw --offline --batch-mode --no-transfer-progress clean verify
ASEP_VN_T13_DISPOSABLE_DB=true ASEP_VN_T13_MAVEN_OFFLINE=true bash scripts/ci_verify_delivery.sh
```

## CR14 review corrections (local only)

The gate now accepts the VN-T14 request rather than synthetic `DeliveryIntent`,
and binds hard-coded project/path/branch/protection restrictions plus an independently
loaded Owner grant. Both inspectors record the whole collection window; freshness
is measured from its beginning. Complete inventory requires explicit consistent
single-page metadata and object-array shape; links are never followed, and a
nonempty Link or missing pagination metadata leaves the inventory INCOMPLETE.
Reports use exclusive no-follow creation and one already-open 0600 file handle.

Earlier authenticated reports are historical snapshots, not new acceptance under
these stricter checks. No live setting was revalidated and no credential file was
used in this correction batch. All live-enrollment/candidate/verifier/credential/
executor/remote-fault blockers remain. Exact correction commands, results, changed
files and revised blocker matrix: `/tmp/asep-cr14-results.md`.

Correction verification: 259 focused cases passed; Java `clean verify` passed
(920 discovered, 865 passed, 55 opt-in skips). The explicit PostgreSQL fixture
passed 54 database plus 7 contract cases and all five replay repetitions. Tracked
and untracked VN-T14 whitespace checks passed. These are local results only.

| Revised blocker | Current disposition |
|---|---|
| CR14-01..04 local corrections | Implemented and verified with deterministic fixtures |
| Selected project/source/main scope | Owner authorization preserved; hard scope checks added; no live trusted grant provider configured |
| Current complete safe-CI/enrollment evidence | BLOCKED; no new remote reads; historical snapshots cannot satisfy corrected freshness/completeness |
| Actual sealed candidate and independent verification | Missing trusted controller/runtime/receipt evidence |
| Usable credential, durable VN-T14 storage and live transport | Unavailable/not implemented; no credential action or writer activation in this batch |
| Remote delivery, fault/revocation cases, CI and human merge | NOT RUN; no acceptance or success inferred |

## 2026-10-28 — B2.1 local verification and collision hardening

This addendum records newer local evidence. Earlier verification counts and
implementation limitations in this document are historical snapshots; they
must not be interpreted as the current implementation inventory.

- CR14-05: trusted admission validation precedes new ledger admission.
  Authorization failure does not reserve an operation identity.
- CR14-06: `reconcileOnly` supports scoped remote reconciliation without a
  current write grant or external GitLab mutation capability. Reconciliation
  can still update durable local observation state.
- R2-01: unclaimed push and Draft MR collision states remain `BLOCKED`
  after external objects disappear. Both cases have PostgreSQL-backed
  coordinator regression tests.
- R3-01: migration V4 rejects SQL attempts to rearm unclaimed `BLOCKED`
  push/MR steps. A dedicated schema regression test verifies the guard.
- CI discovery thresholds now require at least 8 VN-T14 schema tests
  and 18 VN-T14 coordinator tests.

Verification recorded on 2026-10-28:

- Flyway V1-V4 migrate, repeat and validate: PASS.
- Disposable PostgreSQL integration: 112 tests, zero failures/errors/skips.
- VN-T13 concurrent replay: 5 successful repetitions.
- VN-T14 concurrent CAS: 3 successful repetitions.
- Owned disposable database cleanup: PASS.
- Java 21 offline `clean verify`: BUILD SUCCESS; 971 discovered,
  865 executed, 106 skipped. The skips comprise 105 opt-in PostgreSQL
  tests and one opt-in GitLab live-read smoke test. PostgreSQL coverage
  was executed separately in the disposable fixture.

### Current readiness boundaries

| Capability | Current disposition |
|---|---|
| Durable VN-T14 ledger and local ACL tests | Implemented and verified locally |
| Synthetic coordinator, claims and reconciliation | Implemented and verified locally |
| Trusted live Owner-grant and receipt providers | Not configured or verified |
| Current complete safe-CI enrollment | BLOCKED / NOT VERIFIED |
| Live GitLab writer and authorized execution wiring | NOT IMPLEMENTED / NOT ACTIVATED |
| Real push, Draft MR and remote fault/revocation acceptance | NOT RUN |
| Human merge and end-to-end acceptance | NOT RUN |

The local checks do not authorize GitLab mutation or establish VN-T14
ticket acceptance. Migration V4 was verified on disposable PostgreSQL;
before adoption, confirm that its edited checksum has not already been
applied to any persistent database.
