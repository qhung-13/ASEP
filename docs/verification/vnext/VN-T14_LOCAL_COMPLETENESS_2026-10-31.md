# VN-T14 local implementation and completeness — 2026-10-31

Owner priority: finish all safely implementable repository code and local
verification before Safe-CI enrollment, credential work or deployment verification.
VN-T14 is the only active ticket. Real GitLab acceptance remains BLOCKED and the
ticket is not DONE. Local implementation assessment is IN PROGRESS until the
validation and completeness audit below are finished.

## Baseline and provenance

Started at `9b0a65fafc8891194533e79c1d148e436d61e950` on
`feat/vn-t14-authorized-gitlab-test-project-draft-mr-delivery`, 11 commits ahead of
the locally recorded upstream, with 24 staged files, no unstaged tracked edits and
untracked `specs/`. No remote refresh was used. The initial staged binary patch
and 308 original file hashes (including the original specs) were preserved under
`/tmp/asep-vnt14-oct31-*`. The original index was not reset or discarded.
The October 30 report's final-clean claim was prospective; current evidence must
include the uncommitted runtime/deployment continuation until actually committed.
Old release logs are unavailable and their reported passes are historical only.

## Checkpoint fixes

- Uncertain Docker create retains its synced nonce/image journal on missing or
  malformed CID. Normal cleanup checks nonce and image. An absent unacknowledged
  remote result stays available for protected manual disposition. No broad prune.
- The installed command and controlled Java/Go fixture share the package-private
  `ProtectedDeliveryExecutor` composition. Production still authenticates installed
  root-pinned code/config/UIDs; fixture capability injection is not a host identity
  or real Owner approval. No public/default live entrypoint was added.
- Synthetic enrollment tests load actual bounded files and verify report hashes,
  subject/provenance/request binding, clocks and separate dummy-secret fingerprints.
  Positive/fault/recovery tests use the real gate, JDBC ledger/inbox, native Git and
  fake MR service. Cross-process tests also use actual authenticated Go contracts.
- October 30 provenance corrected without converting old results into current PASS.

## Validation

Java 21.0.12.1 and Go 1.27.3 were used. Commands run from the repository root
unless the working directory is shown. Maven was offline. Docker used only cached
images and the owned nonce/label-checked disposable fixtures. No developer DB,
real GitLab capability, host account or installed service was used.

For commands below:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:/home/switer/.local/opt/go1.27.3/bin:$PATH"
```

| Current execution | Command | Actual outcome |
| --- | --- | --- |
| Initial focused, before owned opt-ins | From `backend`: `./mvnw --offline --batch-mode --no-transfer-progress -Dtest=ProtectedDeliveryRuntimeTest,IsolatedVerifierRecoveryTest,ProtectedDeliveryExecutorPostgresTest test` | 49 discovered; 35 executed, 14 fixture skips, zero failures/errors |
| Owned Java PostgreSQL | `ASEP_VN_T13_DISPOSABLE_DB=true ASEP_VN_T13_MAVEN_OFFLINE=true bash backend/scripts/ci_verify_delivery.sh` | 160 selected tests, zero skips/failures/errors; V1–V7 migrate/repeat/validate, five replay and three CAS repetitions, owned cleanup PASS; run `f474077711c70154737002ab3d1127fb` |
| Full Java regression | From `backend`: `./mvnw --offline --batch-mode --no-transfer-progress clean verify` | 1,170 discovered, zero failures/errors, 156 skips: 153 DB cases, two Docker cases, one intentionally disabled live GitLab read smoke |
| Docker + runtime/inspection + TLS/native Git | Command below, from `backend` | 72 tests, zero skips/failures/errors; actual networkless verifier and owned recovery executed |
| Full Go/PostgreSQL/cross-process regression | Command below | PASS: 150 top-level PASS entries (142 unique names), 303 subcase PASS entries, zero failures/skips, all 16 cross-process scenarios; V1–V13 + Java V1–V7 compatibility, vet/race, actual PostgreSQL restart and owned cleanup passed |

```bash
# From backend; the JAR was produced by the preceding full regression.
chmod 0644 target/asep-backend-0.0.1-SNAPSHOT.jar
ASEP_FIXTURE_IMAGE_ID=$(docker image inspect --format '{{.Id}}' flyway/flyway:11)
./mvnw --offline --batch-mode --no-transfer-progress \
  '-Dtest=ProtectedCandidateControllerTest,IsolatedVerifierRecoveryTest,ProtectedDeliveryRuntimeTest,ProtectedEnrollmentInspectionTest,GoDeliveryAuthoritySourceTest,GitLabDeliveryAdapterTest' \
  -Dasep.delivery.controller.docker=true \
  "-Dasep.delivery.controller.image=${ASEP_FIXTURE_IMAGE_ID#sha256:}" test

# From repository root, full Go harness (includes vet/race and cross-process cases).
ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T12_MAVEN_OFFLINE=true \
  bash orchestrator/scripts/ci_verify.sh
```

The cached verifier image ID is
`782c5c207ffb5ac6336139fda4f4295bd9991ef63ad36919406d4268740069bb`.
Logs from this execution: `/tmp/asep-vnt14-oct31-focused.log`,
`/tmp/asep-vnt14-oct31-java-pg.log`, `/tmp/asep-vnt14-oct31-java-full.log`,
`/tmp/asep-vnt14-oct31-docker-focused.log`, `/tmp/asep-vnt14-oct31-go-full.log`.
Counts overlap between suites and must not be summed as distinct tests.

The first unprivileged Maven invocation failed before tests because the sandbox
could not create Maven cache locks. The same offline command passed with approved
local sandbox access. Disposable Docker tests likewise needed sandbox access to
the local daemon. Neither approval authorized host provisioning or external use.

## Completeness review against the bounded VN-T14 scope

Authority: root `AGENTS.md`, `docs/INDEX.md`, accepted ADR-VN-01 component boundaries,
execution contracts (candidate/evidence binding and separate external operations),
and the fixed VN-T14 probe/enrollment contract in `DELIVERY_AUTHORITY_IMPLEMENTATION.md`
and `backend/db/VN-T14-EVIDENCE-AND-LIFECYCLE.md`. ADR-VN-04 deployment/authentication
choices remain proposals until operationally approved. The broader Jira/AI feature
and vNext verification backlog are not silently included in this ticket.

| Required capability | Existing implementation reused / current proof |
| --- | --- |
| Protected sealing + independent verification | `ProtectedCandidateController`, indexed atomic `ControllerEvidenceStore`, immutable Git/manifest checks, protected `ProbeVerificationMain` in networkless UID65534 Docker; controller/preparation and actual Docker tests |
| Go workflow authorization/lifecycle | `ControllerDeliveryFlow`, controlled Go admission/leases/review/authorization; V11–V13 immutable evidence/current policies and sole Go state writes; controlled and authority integration tests |
| Java protected delivery runtime | Installed UID/hash/config checks, shared `ProtectedDeliveryExecutor`, actual protected-record reader and strict inspection; 13 new owned runtime and eight inspection cases; Java/Go fixture uses same assembly |
| Owner Grant enforcement | Java V5/V6 issuer/runtime separation, authenticated DB audit actor, request hash/expiry/revoke checks at send boundaries; 22 grant integration tests plus existing unit/security negatives |
| Real write protocol with fake transport | Bounded SHA-1 Git smart-HTTP receive-pack and controlled Draft MR API in `GitLabDeliveryAdapter`; native receive-pack, collisions/lost responses/drift/revocation fixtures; no live credential needed |
| Durable push/MR operations | Distinct immutable send claims and CAS observations in Java ledger; V4/V7 and PostgreSQL coordinator/ledger/native-writer tests; no blind resend or compensating deletion |
| Outbox + authenticated callbacks | Go V13 dispatch fencing, Java immutable inbox before ACK, stable callback IDs/hash; explicit ACK decoding, real mTLS and durable outcome/replay tests |
| Idempotency/cancellation/policy/revision fencing | Existing Go leases/run-version/policy epochs + candidate binding, current Java gates and sticky residual race; concurrency and negative suites |
| Crash/UNKNOWN/reconciliation | Original IDs and actual remote effects survive restart/lost response/reorder; uncertainty never re-arms sends; synced owned verifier journal now retains missing/partial CID proof; actual-process restart/fault scenarios |
| Default deny + deployment preparation | No Spring writer/default service activation; placeholders/checkout identities denied, separate root permit and live opt-in, root-pinned installation checks and disabled reviewed templates; local parser/default-deny tests, no deployment |

Security review covers candidate/hash binding, permission ownership and bounded
strict JSON, fixed origins/paths/no redirects or proxies, separate read/write
capabilities, no controller secrets in verifier subprocesses, immutable DB roles,
claim/CAS races, callback replay and no workflow writes from Java. Existing applied
migrations are preserved. No second ledger, duplicate writer or unrelated roadmap
work was introduced.

Combined diff reviewed against the original staged patch and bounded contracts:
no credential-bearing live configuration, new Spring activation, cross-owner SQL,
remote merge/approve/delete path or migration rewrite was introduced. All original
staged paths remain included, with the two review findings corrected. The 31
intended paths exclude `specs/` and ignored graph/build artifacts. SHA256 checks
confirmed all seven original specs and all 20 existing migrations unchanged;
all other original file changes are confined to the reviewed paths.

`bash -n` passed for both harnesses and both deployment scripts; all four deployment
JSON documents parsed; `preflight --dry-run` and both Git whitespace checks passed.
Graphify refreshed the existing ignored graph using only local AST extraction:
`graphify extract . --code-only --no-cluster` (3,586 nodes / 11,311 edges). No
repository content was sent to an external model. The original generated graph
was used only for navigation; findings were checked against actual source/tests.

All blocking findings from the focused review are resolved and local validation
is complete. No additional safely implementable component gap was found in the
bounded VN-T14 acceptance matrix. Final checkpoint identity and the concise Owner
handoff will be recorded after the local commit and post-commit assessment.

