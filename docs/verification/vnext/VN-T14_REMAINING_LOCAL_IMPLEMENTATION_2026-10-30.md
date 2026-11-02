# VN-T14 remaining local implementation — 2026-10-30

Baseline `4c565902e0492262200609d8532be3ebe8908faf`; feature branch unchanged.

Provenance correction, 2026-10-31: this is historical execution evidence. The
runtime/deployment/report continuation was still staged at HEAD `9b0a65f`; it had
not become the final local commit described below. The four principal referenced
`/tmp` release logs were no longer retained at review. Their results are reported
historical outcomes, not newly reproduced results. Current fixes, Owner priorities
and reproducible validation are recorded in
[the October 31 local assessment](VN-T14_LOCAL_COMPLETENESS_2026-10-31.md).
This report distinguishes repository-side implementation from operational trust
and real GitLab acceptance. No live token, remote mutation, host account, service
installation or production certificate is authorized by this execution.

## Controller evidence and explicit upstream integration

`ProtectedCandidateController` implements the existing single-file harmless probe
profile. It owns the candidate writer, constructs the exact commit/tree/blob from
an independently supplied base, closes object writers, and sends the snapshot
read-only to `ProbeVerificationMain`. No candidate script or build code is run.
The protected harness actually executes two registered integrity/content checks;
no-tests, skips, failures, wrong profile/image or expired proof cannot issue a seal.
These checks are scoped to the probe; they are not a software-quality threshold for
arbitrary generated code.

`IsolatedProbeVerifier` uses an already-cached image by content ID, never pulls,
runs UID/GID 65534 with no network/capabilities/host socket, a read-only filesystem
and candidate/harness mounts, bounded memory/processes/time and cleared parent
subprocess environment. The controller alone holds Docker capability. It checks
ownership labels before cleanup. Controller batch publication uses exclusive files,
fsync, a publication lock and atomic directory rename. Restart reuses the original
unexpired batch; interrupted issuance exposes no partial receipt. Corruption is
checked against the batch index and Git hashes. Host filesystem ownership remains
the trust root; replacing an entire batch as the trusted controller is outside
what checksums can defend. The operator now reads the same indexed batch.

The current host denies Bubblewrap user namespaces. The owned Docker fixture ran
the real pinned harness successfully, but the controller/human/executor accounts
are still simulated by fixture configuration, not independent operational users.
No operational isolation or genuine human approval is claimed.

Go V12 adds protected verification profiles, immutable controller submissions and
review receipts. New `CONTROLLER_PROBE` admission explicitly starts IMPLEMENTING;
Jira identity fields say not-applicable, and investigation/planning are not created
or marked successful. This is the small controller-driven integration path
explicitly authorized by the current master prompt; the normal MANUAL path stays
on its existing investigation flow. A separate issuer role validates the submitted
seal/verification against current protected profiles. Go runtime alone completes
the real producer lease and atomically enters REVIEWING. The issuer then loads a
narrow committed context view to issue the existing V11 review record; the original
`AuthorizeDelivery` performs DELIVERY_PENDING authorization. Each boundary is
idempotent and partial progress cannot grant delivery authority. Runtime cannot
issue proof, and issuer cannot update workflow state. No Java orchestration SQL
write was introduced.

Initial validations (more complete regression evidence follows as slices finish):

- Java controller/preparation run: 72 discovered, zero failures/errors, one explicit
  Docker-only skip; actual child harness used for positive unit evidence.
- Dedicated Docker harness: one executed, zero failures/errors/skips; cached image
  ID `782c5c207ffb5ac6336139fda4f4295bd9991ef63ad36919406d4268740069bb`.
- Go owned V1–V12 fixture: existing nine authority tests plus four new controlled
  workflow tests and eight proof-negative subcases passed, race/vet/unit checks and
  label-owned cleanup passed. No state seeding is used by the positive controlled
  workflow test. Struct proof fixtures in issuer-negative tests do not claim real
  independent-verifier issuance; the actual Java harness is exercised separately.

The following sections record the subsequent implementation and final validation.
VN-T14 acceptance remains NOT VERIFIED.

## Offline writer and durable Java integration

The narrow writer now implements SHA-1 Git smart HTTP receive-pack, sending exactly
the sealed new commit/tree/blob with zero old OID for atomic source-ref creation.
It does not use GitLab's commits API to manufacture a different commit. It never
updates/deletes main or another branch, uses no local Git credential configuration,
hooks or shell, follows no redirects/proxies and applies bounded whole-response
HTTP limits. A real native `git-receive-pack` fixture validates the pack and rejects
an intervening source-branch collision. Unsupported wire capabilities fail closed.
The Draft MR API sends only controlled source/target/title/body, with no merge,
approval or delete capability. The server's actual head/IID are retained even when
source drift makes the result unusable. Closed MRs cannot confirm delivery. Missing
pagination metadata or Link headers keep inventory incomplete.

The existing coordinator/ledger remain the send authority boundary. The adapter's
mutation guard checks current authority and an existing durable step claim. Read
and write credential capabilities stay separate. No default live transport/bean or
real credential is configured; the public fixture factory accepts literal loopback
HTTP only. This is writer code with offline protocol evidence, not GitLab acceptance.

Integration testing exposed a stale-CAS window when the adapter retained an MR IID
before coordinator reconciliation. The coordinator now re-reads on bounded CAS
contention; partial observations retain known MR mismatches immediately, and effect
retention itself retries only local CAS. No external mutation is retried. Added
post-send authority validation records residual race when a grant, policy or lease
becomes invalid while a remote effect occurs. Observed identities/heads stay intact.

Java V7 adds an immutable authenticated intent inbox, separate from send claims.
`DeliveryMessagePump` commits receipt before transport ACK and retries callbacks
only with the original stable callback IDs. A transport receipt never confirms an
external effect. Existing VN-T13 behavior/migrations remain preserved.

Validation: 11 native-Git/API unit cases passed; 49 existing inspection/pagination
and two actual mTLS authority cases passed (62 combined, no skips). Owned Java
V1–V7 fixture passed 114 existing + 22 grant + 10 writer/inbox cases = 146 selected
tests, no skips, plus five VN-T13 replay and three VN-T14 CAS repetitions and cleanup
(run `52c99f091a1a229bf920236788fc145b`). Early compile/fixture failures were fixed;
one new durable drift assertion initially caught the CAS window described above.

## Authenticated durable intent and outcome integration

Go V13 adds bounded delivery dispatch leases, an immutable callback inbox and
monotonic remote observations. An executor mTLS identity receives an exact
operation/body-hash-bound message and commits Java V7 inbox before ACK. Five
transport claims maximum, stale claim tokens fenced, same body/ID replay only;
no ACK is interpreted as a push or MR success. Java commits immutable step claims
before separate writes and publishes stable callback IDs/hashes. Go independently
checks its original authorization, current policy/epoch/version, evidence expiry,
remote candidate and residual race before COMPLETED_DRAFT_MR. Cancellation and
revocation preserve observed effects. Only Go writes workflow transitions.

New/reordered callbacks retain every previously observed attributed IID and last
confirmed candidate, preserve an older race, and cannot duplicate completion or
roll it back. New drift after completion moves the workflow to
RECONCILIATION_REQUIRED; an unchanged higher revision does not invent a race.
Rollback on late audit failure leaves neither a transition nor an acknowledged
callback. Source-proof revocation also invalidates the derived review through a
narrow forward-migration trigger. All earlier migrations remain byte-identical.

The protected Go `serve-delivery` command is separate from development `serve`.
It requires a root-pinned installed binary, real distinct UIDs, protected keys,
TLS1.3, fixed URI SAN roles, isolated runtime/issuer roles and loopback/TLS DB
configuration. Java uses the same bounded pinned mTLS channel for authority,
controlled admission/lease/heartbeat/review, intents, cancellation and callbacks.
Unknown/duplicate JSON fields, invalid identity, redirects, oversized bodies,
unavailable authority and wrong route deny. No agent-facing result endpoint,
synthetic bearer fallback or Java workflow SQL was added.

`ControllerDeliveryFlow` uses actual Go tasks and leases, cancels verification on
heartbeat loss, submits real protected controller receipts and reuses the original
unexpired batch after producer restart. It never issues an Owner grant. An
interrupted publish cannot be recovered by refreshing proof timestamps.

## Protected runtime preparation and enrollment separation

`DeliveryRuntimeCommand`, `ProtectedDeliveryRuntime` and the disabled templates in
[deploy/delivery](../../../deploy/delivery/README.md) provide explicit installation
checks, controller preparation/cancellation, bounded delivery and read-only
reconciliation. No default Spring bean or background writer is enabled. Checkout
execution and sample UID/hash placeholders deny before secrets/network. Installed
wrappers clear inherited JVM options, disable dumps, and never elevate a UID.
Actual host installation, users, firewall, service keys and root permissions were
**not** provisioned. The current host's bwrap denial remains an operational gap.

Live writes require a separate root-owned, exact-request-bound operation permit
in addition to the DB Owner grant, current Go policy, protected candidate and
current inspection. A configuration flag alone cannot authorize anything. The
permit reader verifies every sanitized read-only inventory record's digest,
collector provenance, exact candidate/request binding, earliest observation time,
completeness and assessment. HTTP403, omissions, stale/missing timestamps or
partial inventories cannot become VERIFIED. No such live records were created.

Owner-visible inherited inventories are collected separately from Developer
write/read credentials. `ProtectedEnrollmentInspection` combines fresh protected
Owner inventory counts with current Developer project/main/permission checks;
an Owner inspector's ability to push main cannot represent the writer's role.
Read/write secret fingerprints must differ, and the independently reviewed writer
must be Developer with exact api/write_repository scopes and valid expiry. Live
credential loading occurs only in the protected explicitly enabled path; no real
credential was read by this execution. Read-only reconciliation does not require
or read a write token, live permit or current grant.

The verifier now supports a protected job journal and exclusive recovery lock.
After restart it searches only the recorded nonce, validates exact ownership
label and image ID, removes that owned container and preserves ambiguous proof.
A missing create ACK/CID can recover by nonce without global container pruning.
Tests prove an independently labelled fixture container survives recovery. Normal
cleanup retains failure rather than claiming success on ambiguous ownership.
Published candidate objects remain read-only; fixture cleanup changes only its
own recorded temporary directory permissions after all processes finish.

The deployment README contains the Owner read-only evidence procedure, limitations
of UI/read_api/custom-permission/admin-attestation mechanisms, full inherited CI/
runner/secret/deployment checklist, protected account/JAR/DB/mTLS setup, restart/
rollback checks, and credential lifecycle. The previously deleted token remains
unavailable. Its reported still-active server credential needs separate Owner
revocation/expiry disposition; this execution did not recover, rotate or revoke it.

## Local cross-component and negative matrix

`TestDeliveryPipelineAcrossActualJavaGoContracts` exercises real PostgreSQL,
actual Java controller/harness in a networkless UID65534 Docker container, actual
Go state transitions/issuer/profile checks, distinct ephemeral mTLS identities,
Java JDBC grants/ledger/inbox, native Git receive-pack, local fake Draft MR API,
durable callbacks and Go resulting state. No positive upstream state is SQL-seeded.
Fixture policy/profile provisioning and human grant issuance are explicitly
simulated protected authorities, not actual Owner approval or host trust proof.
The child verifier receives no parent credentials, keys, Docker socket or network.

Sixteen cross-process cases: success + executor restart; producer/controller JVM
restart with the same original proof; missing/revoked/expired grant; lost push
response; lost MR response; source drift at MR creation; cancellation during MR;
callback ACK loss; HTTP429; stale policy; cancellation before send; changed target;
existing source; wrong project. Replays after a cleared fault do not repeat a
claimed push/MR create. Restarts retain the actual bare repository and MR identity,
with zero new writes during read-only reconciliation.

Additional required matrix coverage is deliberately shared with existing suites:

| Required denial/fault | Local evidence |
| --- | --- |
| Forged operator/kernel identity | restricted operator tests, real-PostgreSQL actor derivation and issuer ACL negatives |
| Missing/tampered/stale/profile-mismatched independent proof or candidate | controller/preparation 58+14 cases, protected issuer negatives |
| Wrong project/path/source/target/unprotected or pushable main | gate scope matrix, adapter native-Git negatives, protected runtime/permit negatives |
| Concurrent attempts | Java durable claim/CAS and VN-T13 replay tests, Go concurrent callback tests |
| Existing matching Draft MR / closed or moved MR | adapter/ledger/coordinator/reconciliation tests; no duplicate create or delete |
| Source drift normal and lost MR response | native-Git unit + actual JDBC writer tests retain actual IID/head and sticky race |
| Invalid TLS identity, role crossover, malformed/oversized/duplicate JSON | actual TLS handshake tests and service route negatives |
| Missing credential / incomplete Safe-CI / unprovisioned runtime | default-deny gate and protected runtime/permit/report negatives; no live calls |
| Unknown outcome / lost ACK / reorder / restart | Java inbox/callback tests, ten Go outcome tests, 16 cross-process cases and full legacy restart/race harness |

No live Safe-CI enrollment, GitLab CI success or arbitrary AI candidate quality is
inferred from this matrix. Native Git and fake GitLab are local acceptance of the
implementation contracts only.

## Verification commands and actual results

Java used `/usr/lib/jvm/java-21-openjdk-amd64` (21.0.12.1); Go used
`/home/switer/.local/opt/go1.27.3/bin` (1.27.3). Commands below include the exact
opt-ins. All databases were nonce/label-owned disposable PostgreSQL17 fixtures;
none was the developer/Compose database. External approval was used only for
local sandbox restrictions on cache writes, loopback sockets, Docker and Git
index/commit access, never live GitLab or host service provisioning.

From `backend/`, final full Java command:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw --offline --batch-mode --no-transfer-progress clean verify
```

PASS: **1,146 discovered, 0 failures, 0 errors, 143 skipped**. Skips are 140
PostgreSQL-only cases, two Docker-only cases and the deliberately unrun live
GitLab read smoke. The DB and Docker cases were executed separately below.
Log: `/tmp/asep-vnt14-remaining-java-release.log`.

From repository root, final Java disposable database command:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
ASEP_VN_T13_DISPOSABLE_DB=true ASEP_VN_T13_MAVEN_OFFLINE=true \
bash backend/scripts/ci_verify_delivery.sh
```

PASS: **114 existing contract/ledger/coordinator/schema cases + 22 grant cases +
11 native writer/inbox/runtime-role cases = 147 executed, no skips/errors/failures**.
V1–V7 migrate/repeat/validate, five VN-T13 concurrent replay repetitions and three
VN-T14 CAS repetitions passed. Cleanup passed for owned run
`0084bd280520b2da020eeb84784e24cc`. The added runtime-role SQL test permits a
separate Java database without assuming an orchestration schema exists, and denies
the actual issuer role. Log: `/tmp/asep-vnt14-remaining-java-pg-reviewed.log`.

From `backend/`, focused actual verifier/TLS/native-Git command (after packaging
with `./mvnw --offline --batch-mode --no-transfer-progress -DskipTests package`):

```bash
chmod 0644 target/asep-backend-0.0.1-SNAPSHOT.jar
ASEP_FIXTURE_IMAGE_ID=$(docker image inspect --format '{{.Id}}' flyway/flyway:11)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw --offline --batch-mode --no-transfer-progress \
  '-Dtest=ProtectedCandidateControllerTest,IsolatedVerifierRecoveryTest,ProtectedDeliveryRuntimeTest,GoDeliveryAuthoritySourceTest,GitLabDeliveryAdapterTest' \
  -Dasep.delivery.controller.docker=true \
  "-Dasep.delivery.controller.image=${ASEP_FIXTURE_IMAGE_ID#sha256:}" test
```

PASS: **61 tests, no skips/failures/errors** in the final focused release run,
including actual isolated verifier execution, labelled journal recovery and strict
ACK negatives. An earlier 60-case run preceded the ACK follow-up. That follow-up
also used, from repository root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
backend/mvnw --offline --batch-mode --no-transfer-progress -f backend/pom.xml \
  '-Dtest=GoDeliveryAuthoritySourceTest,ProtectedDeliveryRuntimeTest' test
```

PASS: **35 tests, no skips/failures/errors**. A message ACK must explicitly say
`accepted=true`; false, missing/unknown fields, an array and HTTP204 cannot cause
local acknowledgement. HTTP204 means “no intent” only on claim. Logs:
`/tmp/asep-vnt14-remaining-focused-release.log` (61),
`/tmp/asep-vnt14-remaining-runtime-docker-tests-final.log` (earlier 60), and
`/tmp/asep-vnt14-remaining-ack-runtime-focused.log` (35).

From repository root, focused owned Go/cross-component command:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/home/switer/.local/opt/go1.27.3/bin:$PATH \
ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T11_HARNESS_MODE=delivery-authority \
ASEP_VN_T12_MAVEN_OFFLINE=true bash orchestrator/scripts/ci_verify.sh
```

PASS: nine authority + five controlled-workflow + ten message/outcome tests + one
cross-process test with 16 scenarios; no skips. Also vet, race/unit checks, V1–V13
staged migrations, Java V1–V7 and owned cleanup passed. The final staged-review
callback refinement was covered by this command before the final full harness.
Log: `/tmp/asep-vnt14-remaining-callback-final-pg.log`.

Final full Go harness, from repository root (same command was also invoked from
`backend/` as `bash ../orchestrator/scripts/ci_verify.sh`):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/home/switer/.local/opt/go1.27.3/bin:$PATH \
ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T12_MAVEN_OFFLINE=true \
bash orchestrator/scripts/ci_verify.sh
```

PASS: **25 delivery/authority/controlled/message top-level tests plus 16 E2E
scenarios**, and the full legacy suite, vet, race, PostgreSQL restart and populated
migration compatibility checks. Entire harness: 150 top-level PASS records
(142 unique names; deliberate migration-stage repeats), 303 subcase PASS records,
zero failures/skips. V1–V13 orchestration + V1–V7 Java migrations and owned cleanup
passed for run `53ad77ab4a786445592e02580cf9dd7a`.
Log: `/tmp/asep-vnt14-remaining-go-release.log`.

Other executed checks:

```bash
bash -n orchestrator/scripts/ci_verify.sh backend/scripts/ci_verify_delivery.sh deploy/delivery/preflight deploy/delivery/run-java
bash deploy/delivery/preflight --dry-run
git diff --check
git diff --cached --check
graphify extract . --code-only --no-cluster
```

Shell/whitespace/dry-run checks passed. Graphify used local AST extraction only,
fell back to sequential extraction because parallelism is restricted, and refreshed
ignored graph artifacts (final refresh: 3,529 nodes/11,132 edges); no repository
contents were sent to an external LLM. Git diffs and every proposed staged set
were inspected before local commits; `specs/` was never included.

Development failures were not counted as passes: an initial native writer PG
assertion exposed the MR-effect stale-CAS window, fixed in the coordinator; the
first intent test claimed a different queued operation, fixed by exact-operation
claim binding. Cross-process runs initially failed because umask077 made the JAR
unreadable to UID65534, then because a blanket fixture chmod reset the published
copy; the harness now publishes only the read-only JAR and keeps secrets private.
Another run reached correct outcomes but failed sealed-directory cleanup; cleanup
now restores permissions only under its own recorded private root. A wrong-project
fault originally affected the API but not the independent fake inspection; both
now report the same wrong project and the gate denies. Compile-fixture naming and
sandbox cache/socket failures were corrected/re-run, not reported as successful
validation. Final checks have no remaining local test failure. The final replay follow-up
also proves later policy revocation cannot retroactively invalidate an accepted
completion when identical read-only facts arrive; a real later MR mismatch still
moves the workflow to reconciliation, without granting new write authority.

## Security review, remaining limitations and blockers

Blocking local findings fixed: stale MR-effect CAS, actual source-head retention,
post-send revocation/cancellation race recording, source-proof revocation propagation,
reordered IID/head/race loss, newer mismatch after completion, false race from later
revocation of historical completion, permissive message ACK decoding, inherited JVM
options, and the runtime DB guard's assumption of colocated schemas. All mutation
paths remain tied to independent authority and separate durable claims. Actual
SQL/HTTP/Git inputs are parameterized/allowlisted; no candidate code, shell input,
credential-bearing URL or unrestricted remote link enters privileged execution.

| Remaining item / location | Severity / practical limit | Disposition |
| --- | --- | --- |
| `ProtectedCandidateController.removeOwnStaging` | Low: SIGKILL/host loss can leave an unpublished `.issuing-*` directory; it is never an accepted batch | Protected operator inspects orphan staging under exclusive ownership; automatic deletion of another active producer is deliberately avoided |
| `GitReceivePack`, `GitLabDeliveryAdapter.read` | Compatibility: SHA1 supported wire subset and a bounded single MR inventory page; unsupported/truncated observations fail closed | Keep manual reconciliation for incompatible wire/incomplete inventory; do not broaden access or retry a send |
| `ProtectedDeliveryEnrollment` / deployment runtime | Operational blocker: root provenance and true independent account/FS/network isolation cannot be established by test fixtures | Actual protected deployment and negative identity/access tests require Owner privileges |
| Real GitLab and Safe-CI behavior | Acceptance blocker: not exercised, current inherited settings unavailable | Obtain actual read-only evidence and separately authorize live acceptance; never equate local Draft MR fixture with CI success |

| Original blocker | Repository-side result | Real external status |
| --- | --- | --- |
| Protected operator/controller deployment | Commands, guards, verifier boundary, templates and procedures implemented | NOT PROVISIONED / NOT VERIFIED |
| Candidate seal/independent proof issuance | Actual controlled probe writer, hashes, real isolated checks, atomic indexed receipts, restart tests | Real protected identities/evidence NOT ENROLLED |
| Upstream/review/policy issuer integration | Explicit controller-only IMPLEMENTING→REVIEWING→DELIVERY_PENDING path and existing authorization reused | Jira/AI investigation/planning/implementation remain deferred to their tickets |
| Java–Go mTLS runtime | Actual contracts, identity roles, protected listener/client wiring and negative tests implemented | PKI/network/accounts NOT DEPLOYED |
| Intent dispatch/outcome callbacks | Durable exact-body messages, ACKs, bounded retries, monotonic facts and Go outcomes implemented/tested | Production endpoints NOT ACTIVE |
| Safe-CI enrollment | Complete UNKNOWN checklist, separate Owner collector/Developer inspection and strict protected-report reader implemented | BLOCKED: inherited runner/variable/policy/deployment evidence missing |
| GitLab credentials | Protected separate read/write loading, fingerprints, lifecycle/revocation plan implemented | UNAVAILABLE; deleted token not recovered; prior server token disposition requires Owner |
| GitLab write transport | Git smart HTTP plus Draft MR API implemented and offline/native-Git tested | DISABLED; no live write invoked |
| Actual Draft MR/fault acceptance | Local full success/fault/replay matrix implemented and passed | NOT PERFORMED; no real Draft MR or CI result claimed |

Ordered Owner handoff, only after independent review of these local changes:

1. Separately authorize/provision the protected real identities, reviewed artifact
   installation, DB roles/migrations, PKI, filesystem/socket/network isolation and
   effective negative access/startup/restart checks described in deployment README.
2. Collect complete current read-only project/ancestor/instance Safe-CI evidence,
   exact candidate CI/includes and both push/MR behavior. Resolve 403/inherited
   gaps through an authorized Owner/admin read mechanism, without elevating the
   existing Developer writer or silently altering settings.
3. Decide the old reported-active token's revocation/expiry disposition. Separately
   authorize fresh short-lived scoped write and independent read credentials,
   protected provisioning/deletion and the exact staged revocation fault window.
4. Use the installed controller for the real exact sealed probe and independent
   proof, current Go authorization, genuine human DB grant and a separately reviewed
   exact-operation live permit. Explicitly authorize the real delivery/fault run.
5. Observe actual GitLab branch/MR identities, durable Go/Java outcomes, lost-response/
   revocation/drift recovery and CI independently; complete final human review.

No remote push, MR creation, approval/merge, credential recovery/rotation/revocation,
protected host provisioning, feature-branch push or task-status update occurred.
Repository-side implementation is prepared for independent re-audit; VN-T14 and
production readiness remain NOT ACCEPTED. PR #11's current remote state could not
be refreshed: the GitHub connector returned Unknown tool, `gh` was absent (127),
and public page retrieval failed. Historical “open” is not a current verification.
Local changes may be independently reviewed; pushing/merging is not authorized.

## Local checkpoints and preservation

New local commits: `2e1de69` (real probe evidence/controller and Go upstream),
`7407609` (offline Git writer and durable Java integration), `8da6208` (mTLS
workflow runtime, intents/outcomes and cross-process fixture), `9b0a65f` (explicit
ACKs and historical completion semantics), followed by this protected Java runtime/
deployment/report checkpoint. No existing commit was reset or amended. No push
or merge was performed. The exact continuation diff is measured from
`4c565902e0492262200609d8532be3ebe8908faf`, not attributed to earlier VN-T14 work.

The initial snapshot covered 253 tracked files plus the seven untracked VN-T11
spec files. Preservation checks compare SHA256 for every original file, explicitly
verify that all original migration files and all seven specs are unchanged, and
permit changes only in this task's reviewed paths. The earlier statement that the
final tracked diff/index were empty described an intended post-checkpoint state,
not the state inspected on 2026-10-31: 24 files were staged and `specs/` remained
untracked. Ref/reflog inspection showed no runtime/deployment checkpoint after
`9b0a65f`. Prior checkpoints may have been clean; no cause for the incomplete final
commit is inferred. This staged continuation is preserved and reviewed by the
October 31 execution.
The exact patch and changed-file manifest are retained locally at
`/tmp/asep-vnt14-remaining-exact.patch` and
`/tmp/asep-vnt14-remaining-files.txt`. They contain this continuation's changes only.
