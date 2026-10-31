# Protected delivery deployment preparation — disabled, NOT deployed

Engineering templates only; no approval of operational identity or ADR change is
implied. Run `bash deploy/delivery/preflight --dry-run` to print the review order.
There is no installer or apply mode. No example is a usable authorization record.
The only destination is project **87786140**, `qhung-13/asep-m01-sandbox`,
`test/vn-t14/<operation-id>` → protected `main`, Draft MR only. No direct main
push, force update, merge, approval or compensating delete exists in the adapter.

## Host capability topology

Use distinct real non-root UIDs for agent, controller, verifier, human operator,
Java executor and Go authority. Record the actual numeric UIDs in root-controlled
configuration; zero/duplicate UID placeholders are denied. Never grant the agent
sudo, service impersonation, Docker access, controller filesystem access, service
keys or database issuer credentials. Group membership is a capability: the agent
must not join `asep-evidence-readers`, service groups or Docker. Independently test
negative access as the real agent UID, not by declaring IDs in JSON.

- Root owns `/opt/asep/delivery` and `/etc/asep`, every ancestor, configuration,
  installed binary/JAR, CA and public certificate files; no group/other writes,
  no symlinks or multiple hard links. Use reviewed SHA256 pins, not a JAR from an
  agent-writable build directory. Go binary path is fixed at
  `/opt/asep/delivery/bin/asep-orchestrator`; Java JAR path is fixed at
  `/opt/asep/delivery/asep-backend.jar`. Root JAR mode 0644 allows the verifier to
  read its **single read-only mount**, not the controller's private parent.
- Controller owns evidence, verification-job journals, private key and private
  directories. Evidence directories 0750/files 0640 may be shared read-only with
  operator/executor; their parents must be protected all the way to `/`. Never
  grant those readers write access. Journal/private directories 0700, keys 0600.
- Operator owns only its interactive approval credential and review inbox; follow
  the existing restricted `owner-approval` command and V5/V6 authority deployment
  contract. No HTTP grant endpoint or noninteractive approval is introduced.
- Java executor owns its TLS key, database password and separate read/write
  credential files (0700 directory, 0600 single-link regular files). Controller
  holds neither GitLab credentials nor Owner grant database access.
- Go authority holds separate orchestration runtime and review-issuer database
  credentials, not Java's credentials. Java runtime must have no orchestration
  schema access. Root/migrator provisions approved roles and applies unchanged
  history plus forward Go V12/V13 and Java V7. No role/password provisioning is
  performed by these templates. Runtime roles must be non-superusers without
  CREATE/role-admin/bypass-RLS or issuer membership. Runtime validation queries
  enforce the role split. Review issuer cannot write workflow state; Java cannot
  issue its own grants; protected evidence and audit history are immutable.

The trusted controller alone holds Docker daemon authority for the fixed verifier
harness. This is a powerful host capability: isolate its host/daemon from agent
execution, protect daemon access, and audit the effective mounts/processes. A
verifier receives no socket, host network, parent environment, private key or DB
credential. It is UID 65534, no caps, no-new-privileges, read-only, network none,
limited CPU/memory/PIDs, fixed image ID, no pull. Candidate data is read-only;
no candidate executable or CI/build script is loaded. Journal recovery requires
the exact recorded job nonce and image under a protected exclusive lock; unknown
proof is retained for manual investigation. No global Docker prune is allowed.
The verifier syncs its job proof and parent before create. Missing/partial CID or
unacknowledged absence preserves the journal for owned recovery/manual inspection;
absence is not permission to erase uncertainty or retry a create. Offline runtime
and cross-process fixtures share `ProtectedDeliveryExecutor`; their synthetic
records/identities are never evidence that an operational host is enrolled.
Unpublished `.issuing-*` controller directories after SIGKILL require inspection
under exclusive controller ownership; they can never be delivery evidence.

## Artifact and authority startup

Build/review with Java 21 and the repository's Go toolchain. Independently compare
SHA256 of the exact installed artifacts to the reviewed local build; record
reproducibility and dependency provenance. Pin **the same controller harness JAR**
in Go's protected policy/profile and Java controller settings; pin the preloaded
image's image ID (not a mutable tag). Provision only approved harmless-probe
verification profiles and delivery policies through the separate Go issuer role.
A policy row is not a completed workflow or fabricated verification result.

Provision TLS 1.3 with dedicated internal trust roots and certificate validity
monitoring. Server certificate must have the actual loopback IP SAN; Java checks
normal trust, hostname and a pinned **leaf DER** SHA256. Go also pins certificate
**file bytes** in its root config. Distinct client URI SANs:

- controller: `spiffe://asep.local/candidate-controller`
- executor: `spiffe://asep.local/code-delivery`

Use PKCS#8 private keys, single intended URI, valid client-auth/server-auth EKUs
and only the relevant trust root. Keep CA signing keys outside all service UIDs.
No development bearer endpoint fallback exists. Listener binds IPv4 loopback
only; certificates authenticate calls even from other local users. Host policy
must restrict who can reach the listener/DB/Docker and executor egress to approved
GitLab HTTPS. TLS authenticates peers, not kernel process isolation; verify both.
PostgreSQL connections require verify-full and a matching loopback IP SAN.

Only after external Owner approval/provisioning:

1. Install edited root-owned configurations with real distinct UIDs and pins.
2. Invoke installed Java `DeliveryRuntimeCommand check candidate-controller` and
   `check delivery-executor` through the root-installed `run-java` wrapper (recommended destination
   `/usr/local/libexec/asep-delivery`, see service template). It clears inherited
   Java/loader options and disables core/heap dumps; never import agent environment.
   Checks perform no network calls or secret reads. A checkout is denied.
3. Start reviewed Go `serve-delivery`, independently test valid/wrong/expired
   client identities, unknown routes, oversized/duplicate JSON, unavailable DB
   and denied cross-domain/issuer SQL. Existing development `serve` is separate.
4. Root imports a controller job with operation/grant IDs, policy/epoch, base SHA
   and Safe-CI profile reference. `DeliveryRuntimeCommand prepare <root-job.json>`
   uses actual Go admission/lease/heartbeat, seals the exact probe, runs independent
   checks, publishes receipts, and advances existing Go review/authorization.
   No Jira, AI investigation/planning/implementation is claimed. Store/export the
   exact returned request for human review, including content and object hashes.
5. Controller restart reuses original evidence without refreshing receipt TTL;
   partially issued upstream stages replay the same source proof and epochs.
   Cancellation/policy/source-evidence revocation fences further eligibility.
6. `cancel <root-cancel.json>` uses the controller identity and an exact
   `{runId, expectedRunVersion}` root-imported command; Go validates/fences the
   transition. It never deletes a remote effect or writes workflow SQL from Java.
7. Human operator approves the exact request through the existing interactive CLI.
   This DB grant does not enable a live write by itself.

No periodic writer service is supplied. `once <operation-id>` is an explicit
bounded invocation after **separate** live authorization. Default config has
`liveWritesEnabled=false`, denied before credential or network access. Even true
requires every protected permit/fact/report, credentials matching independent
fingerprints, current Go authority, exact Owner grant, protected receipts,
current project/branch checks, durable inbox and separate push/MR send claims.
No flag alone grants authority. Each send repeats gates and object hashes.

`reconcile <operation-id>` is read-only toward GitLab, independent of a revoked
write credential/grant/permit, and cannot create a new ledger/send claim. It uses
only the dedicated read credential, persists actual facts/callbacks, and publishes
them over executor mTLS to Go. Unknown writes are never retried; response identity,
closed/mismatched MRs, moved branches/target, lost responses and residual races
remain durable. Transport ACK never completes a workflow. Inspect both domain
ledgers and outbox/inbox audit records; a healthy process is not delivery success.

## Owner-operated Safe-CI evidence

Start with `safe-ci-checklist.json`: every entry is UNKNOWN. Prior Developer 403s
and prior UI observations are context, not enrollment. Account for all ancestors
and inherited/compliance settings, not only project-local counts. Do not create
an inspection credential or change a setting as part of using this checklist.

Least-privilege collection options, requiring separate authorization where needed:

| Mechanism | Can verify | Cannot establish alone |
| --- | --- | --- |
| Existing Owner UI session, read-only inspection | Settings actually visible at project/group level; metadata snapshots and inheritance paths | Hidden instance/runner internals, unvisited inherited policies, completeness or CI execution merely from empty screens |
| Separately authorized `read_api` collector at an adequate existing role, GET-only wrapper | Paginated accessible API inventories and timestamped provenance; no writer-token elevation | API-hidden/inaccessible group/instance settings, safe runner execution or deployment absence from a 403 |
| Authorized custom read permissions, if available for this subscription/version | Specific runner/settings metadata at reduced privileges | Availability, coverage or sufficiency without checking each endpoint; no assumption that generic read_api overrides roles |
| Instance/group administrator attestation and reviewed runner configuration | Otherwise inaccessible inherited/instance execution, secret and deployment capabilities | Project/candidate CI safety or exact writer permissions without separate checks |
| Read-only exact Git object/CI review | Exact candidate/base `.gitlab-ci.yml`, all pinned includes, source/MR rules, policy-injected jobs and deployment paths | Runtime runner isolation, secret availability or real pipeline success |

GitLab documents that role **and scope** determine project token permissions and
that token values cannot be viewed again after creation:
[project access tokens](https://docs.gitlab.com/user/project/settings/project_access_tokens/).
`read_api` permits API reads, while `api` permits writes; repository write scope
uses Git-over-HTTP, not arbitrary object creation by REST:
[token scopes](https://docs.gitlab.com/security/tokens/access_token_scopes/).
Runner visibility still requires appropriate roles; reduced custom read permissions
are version/subscription dependent:
[runners](https://docs.gitlab.com/api/runners/),
[custom permissions](https://docs.gitlab.com/user/custom_roles/abilities/).
These documents are procedure references, not observations of the selected project.

For each checklist subject record start/end, exact account/endpoint or UI path,
project/group/instance scope and permissions, every page and totals, gaps, metadata
hashes, and a reasoned assessment for **both source push and Draft MR events**.
Stop on 403, missing metadata, unexpected JSON shape, Link-next, truncation or an
unresolved include/inheritance edge. Follow only preapproved same-origin endpoint
pagination, never arbitrary links. Variable **values**, registry credentials,
private keys, deploy-token values and session cookies must never be requested,
exported or stored. If an API returns secret values, use UI metadata or a trusted
in-memory redacting collector under separate authorization; never persist raw body.

Inspect specifically:

- exact project/namespace, inherited access and target/head; Developer writer role
  and inability to push protected main (do not confuse an Owner inspector's role);
- project/group/instance runner attachment, protection/tags/untagged and MR/fork
  behavior, executor isolation, shared workspace/cache/artifacts, network and secrets;
- project/ancestor/instance variables metadata, environment protection and credential
  exposure under both events; zero project variables says nothing about inheritance;
- candidate and base CI, every local/project/remote/component include at immutable
  identity, defaults, workflow/job rules, parent/child/downstream pipelines, pipeline
  execution/security/compliance policy injections and Auto DevOps;
- protected environments, deployment approvals/permissions, deploy keys/tokens,
  hooks/integrations, job-token allowlists and repository push settings, protected
  resources exposed to MR pipelines. No runner-specific entry does not disable
  enabled instance runners. Draft does not prove pipeline suppression or success.

A protected collector/root reviewer may publish reviewed, sanitized records into
`/etc/asep/delivery-enrollment/evidence/<sha256>.json`. The `ReadOnlyObservation`
record in `ProtectedDeliveryEnrollment` specifies exact subject, collector,
method (`GET` with HTTP200 or `OWNER_UI` with status0), complete flag, earliest
start/end, `SAFE_FOR_EXACT_PROBE` assessment, full requestHash and optional itemCount.
This is an external trust assertion after genuine collection/review, not a code
path that blesses incoming client claims. Preserve fuller provenance separately.
Every record's bytes/hash and exact request binding are checked. A full report
cannot be constructed from missing observations. Required inventory counts are
protected-branches, all runners, variable metadata, deployment environments and
source-push pipeline observations; inventory reports have complete pagination.

The separate root-owned `<operation-id>.json` `Permit` binds requestHash/project/
path/source/main, Safe-CI reference, independent read/write credential **file-byte**
SHA256 fingerprints, Developer role30 with exact api+write_repository write scopes,
credential expiry and all fact record hashes/provenance. Start/end are preserved:
expiry and every read must be within the explicit five-minute inspection window,
never refreshed by later completion or another permit. Require a new collection
if human review exceeds the window. The read credential is separately reviewed
Developer read_api/read_repository, not the Owner inventory capability. Current
project/branch checks use that limited identity; protected Owner inventories are
combined separately. No credential value appears in a permit.

## Credential lifecycle and live acceptance (external, NOT authorized here)

The deleted local file is unavailable; do not search/recover/recreate it. The
previous dedicated server token was reported active and unrevoked, expiring
2026-11-04. Owner should inspect current status/use and separately authorize
revocation or expiry disposition. Repository code has not revoked it. Do not claim
its existence proves access or use it for the new test.

After separate approval, Owner supplies fresh short-lived project-scoped Developer
api+write_repository capability for this sandbox only, directly into protected
executor storage. Use independently authorized read capability for post-revocation
reconciliation; never fall back to a broader Owner credential. A new credential
requires new identity/scope/expiry/fingerprint evidence and exact live permit.
Never put secrets in URLs, Git config, shell arguments, environment, logs or agent
sandboxes. Disable core/heap dumps and unreviewed JMX/JFR; use trusted local secret
provisioning, restrictive files and bounded lifetime. Java's network transports
hold header material in process memory only and close after the bounded invocation.

Approve the exact operation and fault schedule separately. Test success then a
separate staged revocation case with observed push confirmation before revoking
only the designated test token. Revalidate before MR create; no further mutation
after revocation. Retain unknown/actual IID/branch identity if a race occurred,
reconcile with read-only authority, and inspect Go/Java durable facts. No rollback,
blind retry, branch/MR delete, protected-main write or CI success is inferred.
Owner handles server revocation and protected local-file deletion after the
window, records audit IDs and verifies no surviving secret copies. Rotation must
also replace independent fingerprints and permissions evidence, never refresh an
old grant silently.

## Health, restart, rollback and required operational proof

Health checks: installation identity/hash check; mTLS negative/positive read;
DB role and migration history; original seal/report TTL; current policy; bounded
outbox/inbox lag and callback attempts; owned verifier journal; remote read-only
observation. No health endpoint manufactures authority or workflow progression.
Use bounded retries for messages (intent leases capped at five claims); a stuck
consumer requires read-only inspection/manual disposition. Service restart must
retain PostgreSQL records, evidence, journal and remote identities. Re-run the
local real-process restart scenarios with installed identities before enrollment.

Rollback: stop the explicit operation/service, disable live eligibility/permit,
revoke current Go policy and/or Owner grant through the authorized components,
retain ledgers/audit/evidence, and perform read-only reconciliation. Restore a
reviewed compatible binary/config only after migration compatibility review;
never downgrade/drop durable state or pretend remote writes rolled back. Archive
old TLS leaf pins and overlap trust only during an authorized bounded rotation;
revoke old identities and test denial. An expired/bad identity must fail closed.

Operational proof requires real UID/socket/filesystem/network denial, root artifact
pins, valid/revoked mTLS behavior, DB role isolation, actual independently collected
Safe-CI reports, exact human approval and permitted real GitLab success/fault
observations. Local fake services and test certificates prove code behavior only.
