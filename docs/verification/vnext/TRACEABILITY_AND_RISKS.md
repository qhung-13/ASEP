# ASEP vNext — Traceability, Verification and Open-Risk Backlog

**Prepared:** 2026-10-22
**Status:** OWNER-DIRECTED vNext VERIFICATION BACKLOG (2026-10-22) — all scenarios OPEN / NOT VERIFIED; no test execution is implied.
**Legacy scope:** Preserve original V-01..V-14 and M00-02 R-01..R-10, all OPEN unless later evidence closes specific obligations. Do not reassign or renumber those historical entries.

## 1. Traceability rules

- Requirement IDs `VNX-REQ-*` and test IDs `VNX-V*` are *vNext planning IDs*. They do not replace M01 `REQ-001..010`, `PUB-001..009`, `EVAL-001`, `V-01..V-14` or documented policies.
- Every test must distinguish actual runtime evidence from a mocked fixture or static architecture review. A mocked GitLab response is not proof of real GitLab.com token permissions.
- A successful model response is not independent code correctness evidence.
- Approval of this document does not imply PoC execution or pass results.
- Security invariants are mandatory gates and cannot be traded for better model success rate.

## 2. Functional traceability (planning baseline)

| Requirement | Design owner | Verification IDs |
|---|---|---|
| VNX-REQ-01/02 — admission, policy identity | Application intake, Go | VNX-V01, VNX-V02 |
| VNX-REQ-03/04 — investigation/plan grounding | Agent Adapter, Go gate | VNX-V03, VNX-V11 |
| VNX-REQ-05 — bounded automatic transitions | Go, Application policy | VNX-V02, VNX-V06 |
| VNX-REQ-06 — restricted implementation | Sandbox Supervisor | VNX-V04, VNX-V05 |
| VNX-REQ-07/08 — trusted tests/review | Trusted Verification, Go gate | VNX-V05, VNX-V06, VNX-V11 |
| VNX-REQ-09/10 — Draft MR operations/ledger | Trusted Action Executor | VNX-V07, VNX-V08, VNX-V09 |
| VNX-REQ-11 — human merge | Application/external action policy | VNX-V09, VNX-V10 |
| VNX-REQ-12 — local-only | Model Gateway, egress policy | VNX-V04, VNX-V12 |
| VNX-REQ-13 — minimal persistence | Domain owners, lifecycle coordination | VNX-V13 |
| VNX-REQ-14 — recovery/budgets | Go task scheduler/gates | VNX-V02, VNX-V06, VNX-V14 |
| VNX-REQ-15 — accurate UI/status | Application read model | VNX-V10, VNX-V15 |
| VNX-REQ-16 — revocation/removal | Application, Go, Executor | VNX-V07, VNX-V13 |

## 3. Verification backlog — ALL OPEN / NOT VERIFIED

| ID | Required scenario/evidence | State |
|---|---|---|
| VNX-V01 | Eligibility default-deny, duplicate Jira ticket/revision admission and stable idempotent command | OPEN / NOT VERIFIED |
| VNX-V02 | Atomic competing claims, PostgreSQL leases/fencing, stale late completion, crash/restart recovery, retry budgets | OPEN / NOT VERIFIED |
| VNX-V03 | Investigation/Planning produces evidence references, declares missing coverage and ignores injected ticket instructions | OPEN / NOT VERIFIED |
| VNX-V04 | Agent/tool and **verifier test sandbox** cannot access host home, GitLab secrets, PostgreSQL, Docker socket, private controller secrets or unauthorized egress | OPEN / NOT VERIFIED |
| VNX-V05 | Controller seals/rechecks candidate digest and protected test profile; separate unprivileged test sandbox rejects forged PASS, zero collected/disabled tests and later artifact tampering | OPEN / NOT VERIFIED |
| VNX-V06 | Gate allows bounded advance/rework/replan, blocks missing evidence, cancelled/expired/policy-revoked runs | OPEN / NOT VERIFIED |
| VNX-V07 | Trusted Code Delivery checks base/candidate/target SHA, current policy/credentials, safe CI enrollment/MR body, repository/branch authorization and revocation | OPEN / NOT VERIFIED |
| VNX-V08 | **Separate branch-push and Draft MR-creation ledgers** handle lost responses, UNKNOWN, conflicts, remote lookup and partial operation recovery | OPEN / NOT VERIFIED |
| VNX-V09 | No automated merge/approve/protected branch write, no unauthorized review-comment publication | OPEN / NOT VERIFIED |
| VNX-V10 | Owner/Viewer API authorization; no disclosure of new unclassified raw Jira/agent content or secrets | OPEN / NOT VERIFIED |
| VNX-V11 | Reproducible local coding tasks including one-file bug, multi-file bug, failing test, insufficient context and bounded correction | OPEN / NOT VERIFIED |
| VNX-V12 | Pinned Ollama/OpenHands versions, model/context/tool accuracy, full-stack RAM/VRAM/latency, auxiliary model calls and denied cloud fallback | OPEN / NOT VERIFIED |
| VNX-V13 | Jira/raw-data/access policy, SDK sessions/worktrees, candidate sealed handoff, new ledger UNKNOWN retention, cleanup/scavenge, integration removal and restore under approved policy | OPEN / NOT VERIFIED |
| VNX-V14 | Java-Go-Python command/task/result contract validation, duplicate callbacks, lost ack and cross-service identity | OPEN / NOT VERIFIED |
| VNX-V15 | Workflow dashboard accurately distinguishes local verified, Draft MR created, CI pending/passed/failed, human merged | OPEN / NOT VERIFIED |

### Evidence formats (future)

For each test/experiment, collect: pinned commit and test fixture; environment/tool/model version; exact scenario; expected behavior; observed pass/fail; sanitized logs/events/trace IDs; relevant DB/remote state snapshots; safety/retention caveats; reviewer disposition. Keep raw source and sensitive prompts outside long-term logs by default.

## 4. M01 legacy verification dependency mapping

| Legacy ID | Shared theme, **not a merged/closed test** |
|---|---|
| V-01 | GitLab API/token permission feasibility; extends to constrained Draft MR capability but requires new separate proof |
| V-02 | Revision-aware polling/admission; Jira admission is a distinct new surface |
| V-03 | Remote revision freshness and documented residual TOCTOU risk |
| V-04 | External Publication `UNKNOWN`/duplicate reconciliation; Code Delivery gets its own operation ledger |
| V-05 | Atomic claims/fencing/Outbox recovery foundations |
| V-06 | Shadow Mode/conditional Auto-Publish defaults; **not** equivalent to workflow P-02 automation |
| V-07 | Independent AI review evaluation/quality gates; new coding tasks need new benchmarks |
| V-08 | Partial/truncated context, evidence/provenance reporting |
| V-09 | Keycloak/BFF/JWT Owner/Viewer authorization and revocation |
| V-10 | Secrets, prompt injection, egress/credential isolation |
| V-11 | Retention and cleanup (new data classes must be separately classified) |
| V-12 | GPU/local model feasibility (no verified quality claims yet) |
| V-13 | Process/deployment isolation/restart behavior |
| V-14 | Backup/recovery/ledger expiration effects |

No historical V-item is closed or implicitly satisfied by this mapping.

## 5. Risk register — ALL OPEN

| Risk | Impact | Mitigation/design direction | Status |
|---|---|---|---|
| VNX-R01 — Go SQL queue race/lease bug | Duplicate valid stage transitions, loss of safety | Transaction and fault-injection tests; fencing on commit | OPEN |
| VNX-R02 — Agent escape or prompt injection | Secret exfiltration, host modification | Process/OS sandbox, strict tool/egress caps, adversarial tests | OPEN |
| VNX-R03 — False model success | Incorrect Draft MR despite agent PASS | Independent real test/review gate; immutable artifact digest | OPEN |
| VNX-R04 — GitLab write ambiguity/branch races | Duplicate MR, stale/partial delivery | Per-step branch/MR ledger, remote reconciliation, current policy and target/candidate identity | OPEN |
| VNX-R05 — Data policy gaps | Unbounded retention/Viewer access of ticket/source data | Define and approve new data classes/TTLs before real source use | OPEN |
| VNX-R06 — Local model insufficiency | Very low task success, long turnaround | Reproducible benchmark; blocking instead of silent cloud fallback | OPEN |
| VNX-R07 — Cross-language/API drift | Lost/ambiguous commands, broken lifecycle | Versioned contracts, schema validation, idempotent callbacks | OPEN |
| VNX-R08 — Single-host failure/restore | Loss of workflow/ledger context | Backup/recovery and crash consistency proof | OPEN |
| VNX-R09 — Scope creep / unauthorized automation | Loss of human control, feature sprawl | P-01..P-03, explicit deny-by-default and Project Control gate | OPEN |
| VNX-R10 — Historical M01 regression | Existing requirements lost during rebaseline | Preserve historical docs, compare old/new acceptance criteria and live Git | OPEN |
| VNX-R11 — Unsafe CI triggered by agent branch/MR | Secret exposure or deploy before human merge | Enrolled safe CI profile, protected vars/runners, sensitive config guard and fixed MR renderer | OPEN |
| VNX-R12 — Forged test evidence / compromised verifier | Untrusted build scripts spoof PASS or steal credentials | Unprivileged test sandbox, protected controller/oracle, server-bound evidence | OPEN |

## 6. AC ↔ verification cross-map (planning, not passing results)

| Acceptance criteria | Verification | Expected depth / authority |
|---|---|---|
| VNX-AC-01/02/04/07 | VNX-V01/V02/V06/V14 | Stable idempotency + payload hash conflict; one active run; expired lease without reclaim; policy/cancel race |
| VNX-AC-03/05/11/12 | VNX-V03/V04/V05/V10/V12 | Untrusted ticket commands blocked, verifier sandbox/oracle, forged evidence denied, no cloud fallback or Viewer leakage |
| VNX-AC-06 | VNX-V06/V11 | Bounded rework/replan and durable budget; missing test oracle prevents automatic ADVANCE |
| VNX-AC-08/09/10 | VNX-V07/V08/V09 | Base/candidate/target identity, push/MR separate UNKNOWN, CI safe enrollment, Draft confirmation and protected-write denial |
| VNX-AC-13 | VNX-V13 | Producer/consumer handoff, session and child-process cleanup, pending UNKNOWN marker and restore-barrier treatment |
| VNX-AC-14 | VNX-V15 | Bind observed CI to pipeline and commit SHA + observation time; pending/failed/unavailable are not success |

The internal reviewer may be a fresh session of the same local model, but its agreement is heuristic, **not trusted executable evidence**. A hidden/protected behavior oracle should fail on the known-bug baseline and pass on the exact candidate where applicable; zero tests collected or disabled test harness is **not PASS**.

## 7. Minimum fault-injection families (all PROPOSED / NOT EXECUTED)

`RF-*` names below are research/test-plan scenarios, **not new approved tickets** and not replacements for M01/VNX verification IDs.

| ID | Injection / expected durable result | Maps to |
|---|---|---|
| RF-01 | Same command ID+same payload replay = same logical result; same ID+different payload = CONFLICT | VNX-V01/V14 |
| RF-02 | Distinct IDs for same active Jira/repo = no duplicate active run; explicit terminal rerun requires authorization | VNX-V01 |
| RF-03 | Concurrent claims by Go/Python workers = one valid task lease/epoch | VNX-V02 |
| RF-04 | Crash after claim/restart = recoverable task, no false workflow completion | VNX-V02 |
| RF-05 | Lease expires before reclaim; late result/heartbeat = rejected; stale sandbox terminated | VNX-V02/V06 |
| RF-06 | Crash after transition before ACK; result replay = one successor, stable budget | VNX-V02/V14 |
| RF-07 | Cancel concurrent with completion = durable serialized decision; in-flight remote effects still reconciled | VNX-V06/V07 |
| RF-08 | Infra retries + semantic rework/replan + restart = budgets not reset or double consumed | VNX-V02/V06 |
| RF-09 | Outbox dispatch/callback duplicate/out of order = deduped logical operation, no extra external write | VNX-V14/V08 |
| RF-10 | PostgreSQL/policy unavailable at gate = no false ACK, transition or privileged write | VNX-V02/V06/V14 |
| RF-11 | Candidate changed after test, forged digest/path/evidence = gate/executor rejects | VNX-V05/V07 |
| RF-12 | Jira/base/target/policy changes before delivery = revalidate/block and record residual request race | VNX-V07 |
| RF-13 | Push succeeded but response lost or remote branch belongs to different owner = reconcile or BLOCK | VNX-V08 |
| RF-14 | Draft MR succeeded but response lost, ambiguous matching = UNKNOWN/manual escalation, no blind new MR | VNX-V08 |
| RF-15 | Branch confirmed, MR failed/unknown = separate step states, no fake completion/compensating delete | VNX-V08 |
| RF-16 | Integration credential removed during UNKNOWN = no new write; minimal unresolved record/manual reconcile | VNX-V07/V13 |
| RF-17 | OOM/disk full/orphan children/cleanup crash = bounded failure and safe artifact handoff | VNX-V04/V13 |
| RF-18 | Restored DB missing last remote operation = freeze delivery and remote reconcile before resume | VNX-V08/V13 |

Use PostgreSQL and real process interruptions for RF-01..10 at the appropriate PoC gate, not only mocked repositories. Use fake GitLab faults before authorized test project writes; stubbed API behavior is **not GitLab.com feasibility evidence**.

## 8. Gate sequencing (not PoC execution)

1. **Docs adoption gate:** Corrected package and live Git diff reviewed, Owner accepts docs/commit; no runtime capability is thereby VERIFIED.
2. **Contract + synthetic PoC gate:** SDK/controller/tool/verifier placement, authenticated task/evidence contracts, protected oracle, pinned local-only config and bounded profile, no real token/source.
3. **Feasibility and durability gates:** VNX-V03/V04/V05/V11/V12 on real local sandbox/model, and VNX-V01/V02/V06/V14 with PostgreSQL/fault injections; ordering may vary after contracts.
4. **Real-data gate:** Approve narrow Jira read-egress exception, repository enrollment, retention/access/Viewer policies, credentials before real Jira/private source.
5. **Privileged delivery gate:** VNX-V07/V08/V09 with verified candidate vs remote branch head, safe CI/pipeline enrollment, renderer, per-step UNKNOWN and revocation.
6. **Slice gate:** VNX-V10/V13/V15 and applicable historical obligations, honest CI observation and human merge boundary; only then claim validated handoff.

Passing a PoC may reduce a risk but is not approval to disable independent human/security control, Auto-Publish gates, or retention requirements.

## 9. Canonical contracts and evidence format

The [minimum execution contracts](../../architecture/vnext/EXECUTION_CONTRACTS_AND_GATES.md) are normative *design obligations* for corresponding PoC/implementation tickets, not tested mechanisms. Each evidence bundle must record expected durable/remote state, observed state, fixture/model/image/SDK and API versions, run/attempt/operation IDs, sanitization, evaluator, test result and residual risk. A local fixture success never closes an M01 risk or demonstrates real GitLab token capability by itself.
