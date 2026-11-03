# VN-T15 P2 — trusted seal before implementation handoff

Correction evidence,2026-11-03. This addresses only the independent audit finding
that implementation was marked SUCCEEDED before Java candidate preparation.
No ticket status or whole-MVP acceptance is asserted. [VN-T14 live acceptance
remains DEFERRED](VN-T14_DEFERRED_ACCEPTANCE.md).

## Cause and correction

The canonical [execution contract](../../architecture/vnext/EXECUTION_CONTRACTS_AND_GATES.md)
requires a stopped writer and available trusted-sealed manifest/candidate at
`IMPLEMENTING → VERIFYING`. The prior Go gate checked the model reservation,
completed the producer and queued verification before Java prepared the candidate.
The canonical contract is unchanged.

The corrected connection uses existing mechanisms:

1. The supervised Python process exits/is reaped. Existing Java
   `ProtectedCandidateController.produceStoppedProposal` copies bounded bytes,
   closes its own object writers, constructs a read-only snapshot, seals it and
   independently tests it. Its existing atomic publication includes the actual
   request, seal, verification and content-addressed objects.
2. The protected controller registers this existing immutable batch through
   `/internal/v1/controlled-slice-candidate`. The existing issuer validates it
   against the latest pending producer's actual live lease, policy/base and
   finite slice deadline. The existing V12 evidence table/ACLs/immutable trigger
   establish provenance. No caller-declared authority flag, new evidence table,
   review result, Owner grant or workflow update is introduced.
3. Go's implementation handoff locks the run, policy and evidence row. It checks
   the current source fingerprint, run/version, original operation, producer
   attempt/task/claimant/lease epoch, policy ID/epoch, base and expected target,
   request hash, seal/commit/tree/manifest identities, writer-stop ordering and
   freshness. Implementation cannot succeed with only a reservation/proposal or
   an unissued, mismatched, revoked or expired reference.
4. The accepted candidate reference is persisted with the implementation output,
   transition and successor outbox in the same existing transaction/receipt.
   Verification-to-review must consume that same accepted reference. Exact replay
   returns original IDs without renewing evidence; existing lease/version/policy
   checks and read-only uncertainty reconciliation remain in force.

The controller already publishes seal and successful test evidence as one batch.
This correction conservatively finishes that batch before the handoff, avoiding
an additional seal-only publication/evidence protocol. A failed protected test
leaves implementation RUNNING rather than falsely successful; existing finite
REWORK can replace that producer, or a terminal BLOCKED decision can retain the
failure. No receipt TTL, model/correction budget or protected assertion was relaxed.

The new V14 view now exposes only the current RUNNING/LEASED producer for
pre-registration, and the successful completed producer for verification/review.
This removes the prior circular success prerequisite. Existing V1–V13 migrations,
Java migrations, evidence immutability/grants and the installed database are
unchanged. V14 is tested only on fresh owned disposable instances.

## Tests and actual results

Normal host UID, Java21 and Go1.27.3; offline cached dependencies. No sudo, installed
runtime/DB change, operational credential use or GitLab call. Logs remain ignored
under `tmp/vn-t15-p2/`.

From `orchestrator`:

```bash
GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off /home/switer/.local/opt/go1.27.3/bin/go test ./internal/workflow ./internal/deliveryapi
GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off /home/switer/.local/opt/go1.27.3/bin/go test -tags=integration,deliveryauthority -run '^$' ./internal/workflow
PATH=/home/switer/.local/opt/go1.27.3/bin:/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T11_HARNESS_MODE=bounded-slice ASEP_VN_T12_MAVEN_OFFLINE=true bash scripts/ci_verify.sh
PATH=/home/switer/.local/opt/go1.27.3/bin:/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off ASEP_VN_T10_DISPOSABLE_DB=true ASEP_VN_T12_MAVEN_OFFLINE=true bash scripts/ci_verify.sh
```

- Focused Go units and tagged integration compilation: exit0 (the latter is
  compilation only, not a test-execution pass).
- Initial focused disposable run: exit0; happy path,11 safety cases, new missing/
  mismatched-seal integration test,9 authority cases, Go vet/unit/race PASS;
  zero skips and successful owned cleanup. The additional issued-stale-seal
  correction test was added afterward and is covered by the final regression.
- Final full disposable regression: exit0; all4 bounded top-level tests, all11
  safety cases,9 authority cases, legacy controlled/delivery pipeline tests,
  populated V1–V14 / Java V1–V7 checks, Go vet/unit/race, retry/replay and actual
  owned database outage/restart regressions PASS. Zero Go skips/failures;
  recorded owned cleanup PASS. New seal-required test6.73s; issued-stale-seal
  correction test2.91s (host observations, not performance guarantees).

| Regression | Durable assertions |
| --- | --- |
| Actual happy path | Writer exited; actual Java batch exists while Go still IMPLEMENTING/RUNNING/LEASED with zero VERIFYING tasks; registration alone changes no workflow state; implementation acceptance timestamp follows actual seal time; then verification/review/delivery complete |
| Missing / unissued reference | Real implementation result cannot advance with absent/unissued seal; run, attempts, tasks, result receipts, transitions and outbox snapshots remain byte-identical |
| Untrusted or mismatched registration | Wrong run, attempt, policy, source/base, candidate SHA, stop ordering or executor identity rejected through actual Java/mTLS Go boundary; no implementation success/verification task |
| Durable fencing | Current trusted reference cannot be rebound to another attempt, epoch, expected version, policy epoch or source fingerprint; rejected transaction leaves complete workflow bundle unchanged |
| Correction and stale seal | Actual failed protected test remains before implementation completion; existing finite REWORK yields a new producer and new independently verified candidate. Stale producer seal rejected before handoff |
| Already-issued stale seal | Explicit synthetic REWORK fault decision creates a different producer; the old genuine registered seal cannot follow it; no new verification or delivery effect; original evidence retained unchanged. This fault decision is not presented as an observed test failure or live approval |
| Replay / boundedness | Original handoff and correction IDs replay exactly; correction exhaustion, policy revocation, source drift, model/time limits, lost-response reconciliation and separate push/MR/CI/merge observations remain covered |

## Reused independent audit evidence / limits

Unchanged Python adapter/contract tests (4 passed), Java delivery ledger/grant/
writer PostgreSQL tests (160 selected,153 database plus7 contracts, zero skips),
replay/CAS repetitions and the two explicit Docker verifier/recovery methods were
independently audited before this correction. Those source paths and mechanisms
are unchanged here; they are reused rather than rerun as unrelated operational
rehearsals. The revised cross-component regression executes the actual protected
verifier against the current built JAR. The final Java21 build also passed with the revised client/allowlist:

```bash
# backend/
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw --offline --batch-mode --no-transfer-progress clean verify
# repository root
git diff --check
bash -n orchestrator/scripts/ci_verify.sh
```

Java: exit0 / BUILD SUCCESS,1173 discovered,0 failures/errors,156 conditional
skips (1017 executed). The153 DB tests and2 optional Docker methods are reused
from the independent audit as above; the real GitLab read smoke remains skipped.
The changed integration uses the actual current protected Docker verifier.
Whitespace/shell syntax passed. No remote CI or live GitLab smoke is claimed.

Synthetic fixtures/identities and controlled model responses do not prove real
inference, OpenHands deployment, operational isolation, public authentication,
real Jira access, human approval or real GitLab delivery. VN-T14 live feasibility,
response-loss/revocation/drift and expired-certificate acceptance remain DEFERRED.
No installed service, K3s resource, credential, live-write flag or remote state
was changed. During correction verification no staging, commit, push or ticket
update was performed. Subsequent local commit preparation is recorded in the
[synthetic acceptance handoff](VN-T15_SYNTHETIC_VALIDATION.md#synthetic-acceptance--review-handoff).

## Correction paths / preservation

Relative to the VN-T15 worktree at the start of correction (not all prior VN-T15 changes):

```text
backend/src/main/java/io/github/qhung13/asep/codedelivery/GoDeliveryMessages.java
backend/src/main/java/io/github/qhung13/asep/codedelivery/InternalDeliveryHttp.java
backend/src/test/java/io/github/qhung13/asep/codedelivery/DeliveryProcessFixture.java
docs/architecture/vnext/BOUNDED_SYNTHETIC_WORKFLOW_V1.md
docs/verification/vnext/VN-T15_SEAL_HANDOFF_CORRECTION.md
orchestrator/db/migrations/V14__connect_bounded_synthetic_workflow.sql
orchestrator/internal/deliveryapi/service.go
orchestrator/internal/deliveryapi/service_test.go
orchestrator/internal/workflow/agent_contract.go
orchestrator/internal/workflow/bounded_slice.go
orchestrator/internal/workflow/bounded_slice_integration_test.go
orchestrator/internal/workflow/controlled_delivery.go
orchestrator/scripts/ci_verify.sh
```

Pre-correction hash/mode comparison confirms the canonical contract, existing
V1–V13 and Java migrations, all7 unrelated specs, all70 retained local-bundle
files and the Git index are unchanged. All other original work is preserved;
only the correction paths above differed at the end of correction. At that
pre-commit verification checkpoint, branch was
`feat/vn-t15-bounded-jira-to-draft-mr`, HEAD/base
`af67c37e2290a38858c110dee748979db8fbf40e`; nothing was staged or committed yet.
