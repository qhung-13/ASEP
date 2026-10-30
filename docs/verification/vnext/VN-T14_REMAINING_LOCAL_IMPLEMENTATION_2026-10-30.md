# VN-T14 remaining local implementation — 2026-10-30

Baseline `4c565902e0492262200609d8532be3ebe8908faf`; feature branch unchanged.
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

Remaining work in this execution: offline writer and native Git contract tests,
authenticated intents/outcomes and cross-component end-to-end tests, protected
runtime/deployment templates, final regression/security review and Owner handoff.
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
