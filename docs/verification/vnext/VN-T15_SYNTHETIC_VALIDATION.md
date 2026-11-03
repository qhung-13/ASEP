# VN-T15 — bounded synthetic integration validation

Engineering evidence recorded 2026-11-03. This is not a ticket-status update,
Owner grant, live enrollment, deployed inference result or real GitLab acceptance.
[VN-T14 live acceptance remains DEFERRED](VN-T14_DEFERRED_ACCEPTANCE.md).

## Synthetic acceptance / review handoff

On2026-11-03 the Owner reported that independent re-audit closed P2 and returned
**READY for VN-T15 synthetic acceptance**. The final trusted-seal ordering and
additional negative tests are recorded in the [P2 correction report](VN-T15_SEAL_HANDOFF_CORRECTION.md);
that correction supersedes the initial handoff ordering tested below. Local
commit preparation reuses the applicable passing audit evidence without rerunning
unchanged suites. This classification covers the deterministic synthetic scope;
real inference, service authentication, deployment, Jira access and GitLab live
acceptance remain separately gated. VN-T14 remains DEFERRED.

## Baseline and boundaries

`git fetch origin` confirmed `main` contains reviewed VN-T14 commits `528a907`
and `5116927`. `git switch main` and `git pull --ff-only origin main` succeeded
(already up to date); branch `feat/vn-t15-bounded-jira-to-draft-mr` was created
from `af67c37e2290a38858c110dee748979db8fbf40e`. At validation time HEAD was
that baseline; no merge/cherry-pick/reset, staging, commit, push or ticket-status
change was made during those runs. The Owner subsequently authorized one local
VN-T15 commit; push/merge and installed-runtime changes remain excluded.

The selected synthetic fixture binds project87786140, repository
`qhung-13/asep-m01-sandbox`, operation-specific `test/vn-t14/<operation-id>` to
`main`, and only the existing fixed probe file. No actual Jira/GitLab access,
existing credential use, installed service/database change, K3s activation or
VN-T14 operational rehearsal occurred. Existing ignored local bundles and the
seven unrelated untracked VN-T11 `specs/` files were preserved.

## Implemented stage-to-component map

See the [v1 connection contract](../../architecture/vnext/BOUNDED_SYNTHETIC_WORKFLOW_V1.md)
for the finite profile and trust invariants.

| Stage | Actual components exercised |
| --- | --- |
| Eligible Owner-selected ticket | Separate Java `ManualAdmissionProcessFixture` invokes the existing fixture Jira reader/normalizer and `SyntheticManualTicketAdmission`; actual command crosses the process pipe into Go admission |
| Investigation / planning | Existing Go run/task/attempt, lease and outbox; durable finite dispatch reservations; `AgentTaskV1` crosses a cleared-environment pipe into a real Python process; `RunSyntheticAgent` reaps it and `AcceptSliceAgentResult` rejects altered/self-attested authority |
| Implementation | Same versioned task/result contract; only a bounded single-file byte proposal; trusted Java controller seals the actual bytes after producer termination |
| Protected verification | Existing Java immutable object/manifest preparation; separately executed, pinned Docker/JVM harness with no network and protected oracle; actual failed report remains failure |
| Correction / escalation | Existing Go retry receipts/counters, one REWORK allowance; a different producer attempt and newly sealed/tested candidate; no second workflow engine |
| Independent review / authority | Existing protected Go issuer and mTLS controller route; current successful latest producer/version/policy/deadline read from Go state; existing review receipt and authoritative DELIVERY_PENDING transition |
| Draft MR delivery | Existing Java durable inbox, protected executor, native Git/API fake and two-step JDBC ledger; real Git receive-pack/loopback HTTP, separate push and MR observations, authenticated Go outcome callback |
| Authorized observation | Java Application Owner/scope checks precede owning-domain read ports; actual Java peer projects Go stage/budgets/retry/evidence and Java push/MR/CI/merge observations; Viewer/worker denied |

The task is the fixed synthetic probe, not arbitrary Jira implementation. The
controlled Python provider makes no inference/model-quality claim. Reservations
are conservative synthetic token limits, not measured real-model usage. There
is no public worker endpoint or new production auth deployment. Fixture-only
Owner grants/enrollment/credentials cannot mint live authority.

## Synthetic acceptance matrix

Focused owned fixture run: **PASS**, including both top-level bounded tests and
all11 safety subcases, zero skipped tests. Happy path6.37s; safety matrix32.93s
(on this host, not a performance guarantee).

| Case | Observed assertion |
| --- | --- |
| Happy path | Java → Go → Python → Java protected seal/tests → Go review/authority → Java native fake delivery → Go COMPLETED_DRAFT_MR → authorized Java observation; three dispatches/384 reserved tokens; exact admission/stage replay; second active scope run rejected |
| Failed protected tests | Actual isolated harness reports failure; Go BLOCKED; zero Java operation rows, pushes or MR creates |
| Bounded correction | Failure consumes one semantic REWORK; new producer attempt; new seal and independent tests; four dispatches/512 reserved tokens; both ledger steps CONFIRMED; exact native source SHA and Draft/open/main MR independently read |
| Correction exhausted | Second protected failure cannot obtain another correction; Go BLOCKED, zero delivery effects |
| Policy revoked | Actual disposable policy disabled; reservation rejected before child dispatch; BLOCKED, zero delivery authority/effects |
| Stale candidate | Old producer cannot be rebound to a new delivery version after correction; protected route returns409; BLOCKED, zero delivery effects |
| Stale source | Wrong source fingerprint cannot advance; BLOCKED, zero delivery effects |
| Model token/call limits | Next reservation rejected independently for each exhausted budget; ESCALATED, no extra model call/delivery mutation |
| Elapsed limit | Actual database deadline passes (no timestamp rewrite); next reservation rejected; ESCALATED, no delivery mutation |
| Model unavailable | Controlled unavailable response has no proposal/fallback; ESCALATED, no delivery mutation |
| Lost MR response | Native fake creates one actual local MR effect; only original-operation read-only reconciliation follows; repeated reconciliation preserves one push/one create and independently confirms both ledger steps |
| Observation authorization | Owner/scope binding checked before ports; Viewer/worker denied; NO_PIPELINE/NOT_OBSERVED is not CI PASS or human merge |

## Reproduction and actual results

Repository root: `/home/switer/Projects/Personal/ASEP`.
Go executable: `/home/switer/.local/opt/go1.27.3/bin/go`.
Java21 home: `/usr/lib/jvm/java-21-openjdk-amd64`.
Socket/Docker/cache access was run as the normal host user through approved tool
sandbox escalation, not sudo/root. Disposable harnesses require their proof marker,
nonce, dedicated roles and recorded container IDs; neither55432 nor55433 is used.

From repository root:

```bash
python3 -B -m unittest discover -s agent-runtime/tests -v
bash -n orchestrator/scripts/ci_verify.sh
git diff --check
```

Python3.14.4:4 tests PASS (CI also checks Python>=3.12,<4). Shell syntax and
`git diff --check`: PASS. New-file whitespace scan: PASS; a supplemental whole-file
scan found two existing Index Markdown hard breaks, verified unchanged from HEAD.
Secret-pattern scan of all33 intended source/doc paths found no private-key,
GitLab-token or AWS-key values (values suppressed by the scan). This is a bounded
pattern check, not a claim of exhaustive secret detection.

From `orchestrator`:

```bash
GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off /home/switer/.local/opt/go1.27.3/bin/go test ./internal/workflow
GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off /home/switer/.local/opt/go1.27.3/bin/go test -tags=integration,deliveryauthority -run '^$' ./internal/workflow
PATH=/home/switer/.local/opt/go1.27.3/bin:/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T11_HARNESS_MODE=bounded-slice ASEP_VN_T12_MAVEN_OFFLINE=true bash scripts/ci_verify.sh
PATH=/home/switer/.local/opt/go1.27.3/bin:/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T12_MAVEN_OFFLINE=true bash scripts/ci_verify.sh
```

Unit/protocol tests and integration compilation: PASS. Focused harness: exit0,
V1–V14 populated migration/replay/ACL compatibility,9 authority tests, happy path
and11 safety cases, Go vet/unit/race PASS; recorded owned containers cleaned.
Full harness: first run exit1 on the legacy retry rollback regression described
below; final rerun exit0. V1–V14 + Java V1–V7, protected authority, both delivery
matrices, Go vet/unit/race, actual disposable database outage/restart, B0/B1/B2
and RF-06/07/08/09/10 regressions PASS. `TestRetryRollbackAndPermissions` passed
on the corrected code. Owned cleanup PASS; no Go test skipped.

From `backend`, with `JAVA_HOME` as above and its `bin` prepended to PATH:

```bash
./mvnw --offline --batch-mode --no-transfer-progress '-Dtest=ProtectedCandidateControllerTest,SyntheticWorkflowObservationTest' test
./mvnw --offline --batch-mode --no-transfer-progress clean verify
ASEP_VN_T13_DISPOSABLE_DB=true ASEP_VN_T13_MAVEN_OFFLINE=true bash scripts/ci_verify_delivery.sh
```

Initial focused Java run:16 tests,0 failures/errors,1 env-gated Docker test skipped.
The actual isolated Docker verifier is exercised without skips by the cross-component
harness. Java21 `clean verify`: **BUILD SUCCESS**,1173 discovered,0 failures/errors,
156 conditional skips (1017 executed).153 are PostgreSQL tests run separately
by the disposable Java fixture; the other three are two optional Docker tests
and a real GitLab read smoke test. The live smoke test remains forbidden/not run.
Dedicated Java disposable fixture: exit0; all153 PostgreSQL tests executed with
zero skips/failures/errors, native writer11/11, protected executor13/13, Owner
issuer22/22, five concurrent replay and three CAS repetitions PASS. Migrations
V1–V7 validated; recorded owned containers cleaned.

The two optional Docker methods were then selected explicitly (existing local
image by digest, no pull), rather than repeating passing unit tests:

```bash
verifier_image_id="$(docker image inspect --format '{{.Id}}' flyway/flyway:11)"
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw --offline --batch-mode --no-transfer-progress -Dasep.delivery.controller.docker=true "-Dasep.delivery.controller.image=${verifier_image_id#sha256:}" '-Dtest=ProtectedCandidateControllerTest#networklessUnprivilegedDockerVerifierRunsActualPinnedHarness,IsolatedVerifierRecoveryTest#restartScavengesOnlyRecordedNonceAndImage' test
```

Result: BUILD SUCCESS,2 tests,0 failures/errors/skips. Only the real GitLab read
smoke remains unexecuted among the default-build conditional cases. The separate
VN-T14 expired-client-certificate live fault is still unverified.

Earlier failed attempts are retained locally; they are not counted as passes:
initial sandbox commands lacked the toolchain/cache/socket permissions; the first
owned fixture exposed PostgreSQL varchar parameter inference in successor
insertion (fixed); the second exposed a Java fixture
catch-variable shadowing error hidden by IDE-generated class files (fixed and
harness changed to clean compilation, with actual-main regression); the third
exposed the missing exact Java internal-route allowlist entry (fixed). All owned
fixture cleanup completed. Run4 happy-only and run5 full safety matrix succeeded.
The first full regression then caught a legacy retry collision/rollback
regression: generic latest-attempt numbering had avoided the test collision.
Legacy numbering was restored, retaining latest-IMPLEMENTING numbering only for
the new slice correction. The finite dedicated integration **suite** bound was
also raised from120s to180s (outer watchdog180s to240s) after its measured119.17s
runtime; no workflow/lease/evidence TTL or per-run budget changed.
Detailed local logs remain ignored under `tmp/vn-t15/`; no raw operational captures
or credentials were added to versioned evidence.

## Limits / separately gated evidence

- VN-T14 real delivery, fresh enrollment, human approval timing and live fault
  cases remain **DEFERRED**, including the expired-client-certificate fault.
- Real Jira access, local model/OpenHands execution/deployment, operational
  untrusted sandbox and service authentication still need separately authorized
  deployment/evidence. Controlled CI responses cannot substitute for them.
- Java observation is a minimal internal synthetic Application service using the
  existing authenticated-context/policy contracts, not a new public REST/UI or
  proof of deployed Keycloak authorization.
- No real MR URL, post-write pipeline, GitLab approval or merge exists for this
  test. CI and human merge remain independent NOT_OBSERVED facts in the fixture.
- PR CI configuration includes deterministic Python checks and the cross-process
  disposable harness. No branch push/PR/remote CI was run; remote CI is unobserved.
- New migration V14 is tested only on owned disposable PostgreSQL; it is not
  applied to the installed VN-T14 runtime. Historical migration files are unchanged.

## Final VN-T15 commit inventory

```text
.github/workflows/ci.yml
agent-runtime/.gitignore
agent-runtime/README.md
agent-runtime/asep_agent/__init__.py
agent-runtime/asep_agent/worker.py
agent-runtime/tests/test_worker.py
backend/src/main/java/io/github/qhung13/asep/admission/SyntheticWorkflowObservation.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/GoDeliveryMessages.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/InternalDeliveryHttp.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/IsolatedProbeVerifier.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/ProtectedCandidateController.java
backend/src/test/java/io/github/qhung13/asep/admission/ManualAdmissionProcessFixture.java
backend/src/test/java/io/github/qhung13/asep/admission/ManualAdmissionProcessFixtureTest.java
backend/src/test/java/io/github/qhung13/asep/admission/SyntheticWorkflowObservationTest.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/DeliveryProcessFixture.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/ProtectedCandidateControllerTest.java
docs/INDEX.md
docs/architecture/vnext/BOUNDED_SYNTHETIC_WORKFLOW_V1.md
docs/verification/vnext/VN-T15_SEAL_HANDOFF_CORRECTION.md
docs/verification/vnext/VN-T15_SYNTHETIC_VALIDATION.md
orchestrator/db/migrations/V14__connect_bounded_synthetic_workflow.sql
orchestrator/internal/deliveryapi/service.go
orchestrator/internal/deliveryapi/service_test.go
orchestrator/internal/workflow/agent_contract.go
orchestrator/internal/workflow/agent_contract_test.go
orchestrator/internal/workflow/bounded_slice.go
orchestrator/internal/workflow/bounded_slice_integration_test.go
orchestrator/internal/workflow/bounded_slice_observation.go
orchestrator/internal/workflow/bounded_slice_stop.go
orchestrator/internal/workflow/bounded_slice_test.go
orchestrator/internal/workflow/controlled_delivery.go
orchestrator/internal/workflow/retry.go
orchestrator/internal/workflow/store.go
orchestrator/scripts/ci_verify.sh
```
