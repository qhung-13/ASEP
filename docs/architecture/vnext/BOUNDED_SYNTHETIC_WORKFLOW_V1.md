# VN-T15 bounded synthetic workflow — v1 connection contract

Engineering contract for deterministic integration fixtures, not a live policy,
enrollment permit, credential grant or proof of local inference. Existing ADRs,
execution gates and VN-T14 DEFERRED acceptance remain authoritative.

## Component map

| Stage | Existing owner/capability | Bounded connection |
| --- | --- | --- |
| Owner-selected ticket/repository | Java `SyntheticManualTicketAdmission`, fixture Jira reader; Go `Store.Admit` records | Actual Java normalization/Owner gate feeds an existing Go admission; a finite slice profile is inserted in that same transaction |
| Investigation / planning | Go tasks, attempts, leases, result receipts and outbox | `AgentTaskV1` crosses an owned pipe to the Python adapter; trusted supervisor checks binding/scope/shape and Go alone commits the fixed successor |
| Implementation / correction | Python untrusted proposals; existing Go retry engine | One permitted probe path, no privileged tools; the stopped proposal must have an issuer-registered immutable candidate before implementation completes; failed protected checks consume a finite `REWORK` allowance and create another implementation attempt |
| Candidate / protected tests | Java `ProtectedCandidateController`, `TestProjectDeliveryPreparation`, `IsolatedProbeVerifier` | Writer process must exit before controller snapshots proposal bytes; immutable candidate plus independently executed harness output, never model-claimed PASS |
| Independent review | Go protected review issuer, controlled evidence/receipts | Current verification task/producer/profile/version and receipt expiry are loaded independently; review task is assigned to controller, not Python |
| Delivery | Existing Java message inbox, two-step ledger, executor and native fake GitLab | Existing mTLS Go authority/outbox handoff; synthetic grant/enrollment confined to disposable fixture; separate push/MR confirmations and read-only UNKNOWN reconciliation |
| Observation | Java authorized Application projection, owning Go/Java ports | Owner-only synthetic read; stage/budget/evidence/correction and separate push/MR/CI/merge fields; no raw ticket text, source, prompt or credential |

There is one workflow engine and one Java delivery ledger. V14 adds only a finite
synthetic profile and per-lease model-call reservations to existing Go state.
It neither backfills legacy runs nor changes installed databases. No public
agent endpoint, SDK deployment, cloud fallback, scheduler platform or K3s
requirement is introduced.

## Versioned task/result boundary

`asep.agent.v1` tasks carry run/task/attempt IDs, expected run version, lease epoch,
policy ID/epoch, ticket fingerprint, base SHA, stage and operation ID. They include
a finite token reservation and remaining elapsed time. The fixture provider is
exactly `deterministic-local-fixture/v1`; an explicit `--synthetic` flag is required.
Only investigation, planning and implementation roles are accepted. No credential,
SQL, arbitrary command, URL, delivery or approval field is accepted.

Results repeat the exact binding, declare `UNTRUSTED_PROPOSAL`, and contain bounded
coverage/proposal data. Investigation/planning proposals cannot attest protected
tests or independent review. Implementation proposes only the operation's fixed
text file. Go's trusted supervisor validates the actual response, waits for the
child and computes its own proposal digest. A role/status/digest in an agent result
never authenticates a privileged caller. Unresolved investigation context is
rejected rather than silently advancing. Model unavailability has no fallback.

Controlled responses exercise orchestration and contracts; they do not establish
real reasoning, local-model quality, OpenHands feasibility or an operational
untrusted sandbox. Those require separately gated deployment/measurement.

## Finite synthetic profile and decisions

The integration fixture selects180 elapsed seconds,4 model-call reservations,
512 reserved model tokens (128 per dispatch), transport delivery limit2 and at
most1 semantic rework, with no replan. These are explicit fixture numbers, not
new production defaults or a modification of the existing300s receipt rules.

Profile creation precedes execution atomically. A short admission-only lock
permits one active run per selected synthetic scope, including unresolved delivery
reconciliation; it does not serialize model execution. Each dispatched task/lease can
consume one reservation; a repeated reservation is rejected. Reclaimed deliveries
cost another finite allowance. Reservations are conservative upper bounds, not
observed real-model token usage. Deadline and active policy are checked again at
advancement; revoked policy cannot issue new model work or delivery eligibility.
Only Go writes transitions. Stale task/version/epoch/source bindings are rejected.

Stage completion, successor attempt/task, audit transition, logical result receipt
and outbox are one Go transaction. Exact replay returns original IDs. COMMIT
uncertainty uses read-only receipt lookup, never a replacement operation. A bounded
correction uses existing retry records and counter debit; the new candidate must
be re-sealed/re-tested under its new producer attempt. Stale evidence cannot
satisfy the new version. Terminal BLOCKED/ESCALATED decisions create no successor
or delivery intent and remain recordable after expiry without granting authority.

## Delivery and observation boundaries

The [P2 handoff correction](../../verification/vnext/VN-T15_SEAL_HANDOFF_CORRECTION.md)
requires a durable trusted candidate **before** `IMPLEMENTING → VERIFYING`.
Java's existing controller publishes one immutable seal-and-test batch only after
its protected checks pass. The bounded connector conservatively completes that
batch before the handoff, then registers it over the protected controller mTLS
route while the latest producer is still RUNNING/LEASED. Registration issues no
workflow transition, grant or delivery intent. Go locks the run/policy/evidence,
validates the immutable issuer record and all current bindings, and atomically
records the accepted candidate reference with implementation success, successor,
receipt and outbox. A reference in an agent proposal is insufficient.

This uses the existing controller store and protected evidence table; no second
seal system or circular requirement for an already-successful producer is added.
A protected-test failure before batch publication leaves implementation
unfinished; the existing bounded REWORK path handles it there. Verification then
consumes only the exact candidate reference accepted at the implementation gate.

The original VN-T14 controller-probe path retains its version3 contract. Dynamic
versions are accepted only for a durable synthetic slice, matching the current
pending implementation or verification state, latest producer task/lease,
base/policy and finite deadline. Review still requires the successfully completed
producer and its accepted candidate reference.
The issuer role reads a narrow context view; runtime cannot issue review evidence.
No agent result creates an Owner grant or all-verified live enrollment.

Synthetic delivery runs through existing typed Java/Go contracts against the
owned native fake remote. A confirmed synthetic Draft MR is not a real GitLab MR.
`COMPLETED_DRAFT_MR`, CI and human merge remain separate. `NO_PIPELINE` and
`NOT_OBSERVED` do not mean CI passed; no merge or GitLab approval is performed.
The Application projection authorizes Owner and scope before invoking its ports;
Viewer/worker access is denied rather than extending M01 read permissions.

No real Jira access, inference deployment, existing GitLab credential, installed
service or persistent database is part of this contract. Local integration test
results and remaining acceptance gaps are recorded separately from this design.
