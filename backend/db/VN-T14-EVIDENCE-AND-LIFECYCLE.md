# VN-T14 local continuation, evidence options and credential lifecycle

Status: PARTIAL; LIVE MUTATION DISABLED. This is an investigation/proposed test
profile, not completed enrollment, a trusted receipt, or production readiness.
No GitLab setting, role, credential or workflow/task status was changed.

Scope remains project 87786140 (`qhung-13/asep-m01-sandbox`), source
`test/vn-t14/<operation-id>`, Draft MR targeting protected `main` only. Never push,
force-push, merge or approve main. Development branch/base remain
`feat/vn-t14-authorized-gitlab-test-project-draft-mr-delivery` /
`05ab5155c5a65ee8c78527d9310a67d315d021e6`.

## What the local code now does

- `TestProjectDeliveryRequest` binds operation/run/attempt/version, Owner-grant
  reference, policy/epoch, exact base/target/candidate/tree/manifest, controller
  receipt IDs and verifier/safe-CI profile digests. Project, branch prefix and main
  target are fixed. Its length-prefixed digest has a VN-T14-specific domain.
  References in this value do not establish authority or verification.
- `TestProjectDeliveryPreparation` rehashes bounded commit/tree/blob bytes from a
  trusted artifact store before parsing. It accepts one single-parent candidate on
  the exact target/base, adding only `vn-t14-probe-<operation-id>.txt` as mode
  100644 with fixed harmless text. The commit message is controlled. Every other
  root entry, including subtree/CI/configuration object IDs, must remain identical.
  The deterministic changed-file manifest binds both complete root tree/commit
  identities and exact probe bytes. A collision, extra change, symlink, executable,
  merge candidate, unsafe tree entry or mismatched object/hash is rejected.
- It loads seal and verifier records by ID from protected trusted-store ports,
  checks run/attempt/base/commit/tree/manifest/receipt binding, termination-before-
  sealing, receipt age/expiry/order, exact protected harness/image/profile, log
  digest and meaningful fully executed passing tests (no skips/timeouts). Age and
  minimum-test policy must be explicitly supplied; fixture thresholds are not live
  policy. Missing/unavailable stores deny preparation with sanitized errors.
- The Draft renderer accepts only validated identities, fixed prose and hashes;
  no ticket, prompt, caller title/body, Markdown or slash command enters its output.
- `TestProjectRemoteReconciliation` is a pure read-evidence classifier. It requires
  the request hash from a previously durable send record, compares actual project,
  source/target, head and Draft state, and correlates full controlled MR text plus
  intent digest. A title or branch alone cannot claim ownership. Known and newly
  attributable MR IIDs and the last confirmed push head survive mismatch, lost
  reads and ambiguity as historical remote facts. Empty lookup after a send is
  UNKNOWN; multiple matches remain UNKNOWN; insufficient/revoked read capability
  requires manual reconciliation. Source movement and sticky post-send races
  prevent an exact-success result. There is no retry, overwrite or delete method.

The candidate path/name, single-file constraint, ASCII root-name bounds, 4096 root
entry limit and object-size bounds are a deliberately narrow local test profile,
not a general Git implementation. Root and merge **base** commits are supported.
No Git index/ref/object was written and no development commit was made: unit tests
construct synthetic Git object bytes in memory, including known Git hash vectors.
A real candidate/seal/independent verifier execution still does not exist.

## What a trusted Java live executor still needs

1. An Owner-controlled trusted test host/service that reads the actual grant,
   current integration/policy/cancellation state and fresh safe-enrollment evidence.
   Request fields and the agent's test report cannot issue that authority. Any
   workflow integration must preserve Go-owned admission/transitions and Java-owned
   delivery; M01 Publication remains separate. Do not invent production Keycloak or
   a new Go receiver as a prerequisite for a narrowly authorized test host.
2. A protected artifact controller that terminates the producer and descendants,
   independently fetches/verifies the current enrolled base, creates/retains the
   immutable candidate objects and recomputed manifest, and issues a seal receipt
   into its protected store. Delivery rehashes the same objects again at send time.
3. A separate trusted verifier controller with a pinned, protected behavior oracle
   and approved profile. Candidate-controlled code runs only in a restricted
   sandbox with a read-only snapshot, no GitLab/Owner credential, controller/DB
   secret, host home, Docker socket or unauthorized egress. The controller observes
   discovery, baseline/candidate behavior, exit, skips, timeout and logs, then issues
   the exact-bound receipt. Java records from unit fixture stores do not prove
   actual independent issuance, isolation, meaningful live coverage or authenticity.
4. Versioned durable Java admission/storage for the new request, step send claims,
   minimal observed MR identities/heads, residual race and callback/replay state.
   VN-T13 `JdbcDeliveryLedger` decodes synthetic `DeliveryIntent`; it cannot be
   wired unchanged to this new request. Existing migrations/codecs were preserved.
   The new classifier's `RecordedSend` must come from that future durable store,
   never incoming JSON. Its output is not persisted by this local helper.
5. A tightly scoped Java branch transport, credential provider and Draft MR writer,
   with TLS/origin/method/body limits, no redirects/automatic mutation retry, no
   force-push, exact ref/candidate and collision checks, and pre-send durable CAS.
   Credential handling must not pass a token to a Git CLI, hook/filter or verifier
   child. No such transport or live activation switch is installed in this change.
6. Fresh revalidation before **each** send; scoped independent branch/MR lookup
   after each send or lost reply; retention of actual effects under source/target/
   policy races. Persist classifier results transactionally with CAS and callbacks;
   read results alone must not advance a Go workflow. Fault-window controls must be
   enabled only for this enrolled test operation. Complete local/PG fixtures before
   enabling any real test. No generic GitLab exactly-once or remote fencing claim.

After CR14-01 correction, `TestProjectDeliveryGate` accepts only the separate VN-T14
request. It hard-checks the enrolled numeric project/path, exact source/main target,
protected main/no main-push capability and the independently loaded Owner grant's
ID/operation/request hash/scope. Verified flags cannot override these checks. Its
verifier has no live implementation, and this gate is not wired to any writer.
A `Prepared` value or an exact remote match is never write authority.

## Least-privilege evidence collection (proposals only)

The supplied Owner UI observations are retained: instance runners enabled, no
project-specific runner/variables/deploy keys/webhooks shown, Auto DevOps off at
inspection. They are observations with incomplete coverage, not a safety grant.
The previous Developer API's 403s remain missing evidence. No authenticated settings
were re-read in this continuation because the credential file is unavailable.

| Mechanism | Can establish | Cannot establish / privilege boundary |
|---|---|---|
| Existing authenticated Owner session, supervised read-only UI review (preferred first) | Current project/namespace identity, role/account feature availability, visible protections, CI/Auto DevOps settings, full runner/variable/environment/deploy-key/hook pages, policy/integration links; record pagination/filters and time without revealing values | UI absence alone does not prove inherited/group/instance absence, runner-host isolation, externally included CI behavior or future execution. Browser session has Owner write power: navigate/read only; never export cookies/session tokens, reveal secrets, save settings or run a pipeline. No new credential needed |
| Owner-operated sanitized evidence bundle from current UI/settings review | An inspectable record of who inspected which exact scope/head/settings, completeness, unknowns and time; can include group-owner/runner-operator findings | Plain JSON/screenshots are not automatically trusted service evidence. A trusted controller must validate provenance, completeness, freshness and bind the accepted safe profile to the exact operation/candidate. Do not accept agent-authored `safe=true` |
| Separately authorized fine-grained Owner read credential, selected project and read operations only | Potential machine-readable project evidence and remote read reconciliation with specific endpoint permissions, where supported | Availability/endpoint coverage and Owner role checks must be confirmed on this account. No automatic access to parent groups or runner-host settings. Creating it requires a separate explicit Owner instruction; no token was created |
| Separately authorized project access token with `read_api`, sufficient project role for each GET | Project-scoped API reads, isolated from Developer push/MR credential | Higher role is for the separate inspection identity only, never an elevation of the Developer writer. Project role/scope cannot prove group/instance settings. Some runner detail GETs require `manage_runner`; do not add that mutation-capable scope to work around a gap. Use Owner UI/operator evidence or remain blocked |
| Group Owner / runner operator / platform evidence | Applicable ancestor variables/policies/runners; actual self-managed executor/host/network configuration, or documented hosted-runner behavior tied to selected runner identity | This is separate authority/coverage. A project credential cannot grant it. If current scope cannot be established, keep UNKNOWN. Do not request admin access or change membership as an implicit next step |

API scope and account role are independent constraints: `read_api` permits reads
within the token's reach; it does not turn a Developer into an Owner. A legacy
Owner PAT can reach unrelated projects, so it is a broader fallback than a
project-scoped reader; no existing Owner token is silently reused. `read_repository`
does not cover settings, and CI job/deploy tokens are not substitutes for Owner
settings evidence. Fine-grained tokens also enforce the Owner's own permissions.
These are proposals; no new credential, role or setting was created/changed.
The current [fine-grained REST catalog](https://docs.gitlab.com/auth/tokens/fine_grained_access_tokens_rest/)
lists project read actions for Runner, Variable, Protected Environment and Webhook
resources. If the Owner later authorizes a reader, those **Read-only** resource
permissions are a concrete candidate, not Create/Update/Delete/Assign/Trigger.
Revalidate endpoint support, role enforcement and selected-project boundaries on
this account first. Group-variable reads still need separately scoped group access
and a Group Owner; runner API metadata cannot expose the actual host configuration.
This is preferable to adding a broad runner-management scope merely for details.
See [scope definitions](https://docs.gitlab.com/security/tokens/access_token_scopes/),
[fine-grained authorization](https://docs.gitlab.com/auth/tokens/fine_grained_access_tokens/),
[runner endpoint prerequisites](https://docs.gitlab.com/api/runners/) and
[group variable prerequisites](https://docs.gitlab.com/api/group_level_variables/).

## Required coverage of the safe-enrollment record

Bind project URL/numeric ID, actual namespace kind/ancestor chain, current account
features, actor/role, observed target head, candidate/manifest, collection time,
source of each observation, full pagination/filter coverage, policy revision and
explicit validity/freshness policy. For a personal namespace, establish that fact
and ancestor absence through authoritative metadata; a namespace API 404 is not
proof of no inherited policies. Mark each inaccessible/unverified area UNKNOWN.

- Check project variables **and** applicable group ancestors, protected/environment
  scopes, external secret integrations, secure files, job/pipeline/schedule/trigger
  inputs, cloud/OIDC identity permissions and job-token/downstream capabilities.
  Inspect metadata without exporting any value/secret or raw variable response.
- Resolve the actual CI path at target and candidate, all local/project/remote or
  component includes and revisions, workflow/job rules for push/MR, child/downstream
  pipelines, Auto DevOps, compliance frameworks and pipeline/scan execution policies.
  No `.gitlab-ci.yml` at main does not prove an injected/alternate CI path is absent.
  Unresolved mutable includes or inaccessible policies block enrollment. Policy
  injection is a documented capability, not observed active in this project.
- Identify all eligible instance/group/project runners, protected/tag/untagged/
  locked restrictions and actual executor/host/ephemeral behavior. The phrase
  "instance runners enabled" cannot identify an individual runner or prove no
  deployment network/credential access. Hosted-runner documentation is supporting
  platform evidence, not an observation of the project's eligible runners.
- Check protected environments, deploy keys/tokens, integrations/webhooks, deployment
  permissions, clusters/agents/review apps, registry/package and cloud identities.
  Zero existing environments/deploy keys/hooks cannot prove inability to deploy.
- Review the exact single-file candidate and unchanged CI/configuration/scripts;
  unchanged CI can still trigger jobs on push/MR. Do not use Draft state, masking,
  branch protection, CI skip options or a guessed account tier as a safety proof.

[GitLab variables security](https://docs.gitlab.com/ci/variables/),
[MR protected-resource rules](https://docs.gitlab.com/ci/pipelines/merge_request_pipelines/),
[pipeline execution policies](https://docs.gitlab.com/user/application_security/policies/pipeline_execution_policies/),
[hosted-runner behavior](https://docs.gitlab.com/ci/runners/hosted_runners/) and
[self-managed runner risks](https://docs.gitlab.com/runner/security/) inform this
coverage. Actual settings and safe-enrollment acceptance remain unverified.

## Proposed credential and fault lifecycle

1. The old dedicated Developer token remains server-side active per the supplied
   status. Its local file is deleted and unavailable; it cannot be recovered from
   GitLab's token display. Do not recreate the file, search caches or logs, rotate
   the token, or create a replacement silently. Record it as outstanding cleanup,
   not as the usable staged revocation credential. Proposed Owner-session action:
   revoke that specific old dedicated token and record identity/time/outcome,
   subject to the Owner's chosen lifecycle execution. No revocation was performed
   here; no shared credential may be touched.
2. First complete safe enrollment, trusted artifact/verifier setup, durable request
   and live transport fixtures, controlled title/body and separate fault windows.
   Obtain a new dedicated short-lived Developer `api` + `write_repository` setup
   **only after explicit Owner authorization**. Keep evidence-reader and writer
   credentials separate. Do not infer permission from this proposal.
3. Provision only to a trusted Java host via a private outside-repo file/service,
   verify ownership/modes/identity/scopes/expiry, suppress headers/wire logs and use
   no URL/CLI/Git-config/environment credential. Keep controller/agent/verifier OS
   privileges separated; same-user file permissions alone do not prove sandbox
   isolation. No child build/test process inherits delivery/evidence credentials.
4. Stage the ordinary exact push/Draft MR, lost push reply and scoped head lookup,
   lost MR reply and scoped full-identity lookup, stale target before send, source
   movement during MR creation and branch collision. Use distinct recorded
   operation IDs and untouched scoped branches, fresh prechecks, no force/overwrite,
   no compensating delete, no blind resend of UNKNOWN. Record actual heads/IIDs and
   CI observations separately from human review/merge. Planned induced faults must
   not mutate main or an unrelated branch.
5. Run revocation **last**, after a dedicated operation durably records the chosen
   in-flight/UNKNOWN window. Stop scheduling new sends first. Owner revokes only
   the new dedicated writer; Java invalidates its write capability and removes the
   local credential. Do not retain/reuse a revoked token to keep reading. Use the
   independently authorized read capability to resolve actual effects; if that
   capability is absent/insufficient, retain minimal IDs/hashes/status and mark
   MANUAL_RECONCILIATION_REQUIRED. Revocation/cancellation never means rollback.
6. Record token-revocation outcome, zero new sends after local denial, local-file
   deletion, client/process termination and unresolved ledger facts. Cleanup any
   branch/MR only under separately authorized cleanup instructions, retaining
   evidence; never treat a cleanup delete as transaction rollback. Do not touch
   unrelated tokens, settings, branches, ASEP Git refs or Jira/task status.

Token values are shown once and cannot be retrieved again through the access-token
page; rotation is a mutation issuing a new token, not recovery. See
[project-token lifecycle](https://docs.gitlab.com/user/project/settings/project_access_tokens/).
No credential operation in this proposal has been executed.

## Current verification and limitations

The final local Java fixture suite passed 137 tests with zero failures/errors/skips.
Java 21 offline `clean verify` passed: 798 discovered, 743 passed, 55 opt-in skips
(54 PostgreSQL cases and one existing live read smoke). The explicitly enabled
owned PostgreSQL fixture then passed 54 database plus 7 contract tests with zero
skips and all five concurrent replay repetitions; cleanup was independently checked.
Those database tests cover the unchanged VN-T13 synthetic ledger, not a new durable
VN-T14 path. No authenticated project request ran in this continuation.
Exact commands/results, complete diff and preservation hashes are recorded in
`/tmp/asep-vnt14-continuation-results.md`.
Real remote delivery, safe-CI acceptance, independent verifier runtime, durable VN-T14
request storage and all live fault/revocation cases remain NOT RUN / NOT IMPLEMENTED
as applicable. No VN-T14 acceptance, production readiness, CI PASS or human merge is
claimed. Existing VN-T13 code, tests, migrations and unrelated specs are preserved.

## CR14 correction boundary

CR14-01..04 are corrected locally; see the current corrections report for commands
and actual results. Both readers now record start/end times and require explicit,
consistent single-page pagination evidence and an array of object records for an
inventory. Missing metadata, full pages or any nonempty Link remain INCOMPLETE;
links are never followed. Freshness uses the inspection beginning, and invalid
clock windows fail closed. The preflight report writer serializes first and uses
one CREATE_NEW/WRITE/NOFOLLOW_LINKS handle with atomic 0600 creation.

Historical authenticated observations above were produced before these corrections;
they are not accepted as post-correction freshness/completeness evidence. No project
request or credential action was performed in the correction batch. Current safe
CI enrollment, actual sealed candidate and independent verifier runtime, usable
credential, durable VN-T14 storage, live transport and remote fault cases remain
blocked/not implemented or not run. No gate grants authority from caller flags.

Limits: this remains a conservative single-page inspector, so legitimate responses
without the required pagination metadata also remain incomplete. No list traversal
was added. NOFOLLOW_LINKS protects the final output component; its parent hierarchy
must be trusted. A process/storage failure can leave an incomplete private report;
exclusive creation does not promise atomic publication of a complete report or
crash recovery. Consumers must validate successful completion/JSON; this report
cannot itself issue live authorization.

## 2026-10-28 — Durable delivery verification addendum

This addendum supplements the earlier CR14 and continuation snapshots.
Statements above describing VN-T14 durable storage as not implemented
reflect the state at the time of those earlier investigations.

### Local implementation evidence

- The Java-owned VN-T14 ledger persists immutable request identities,
  committed push/MR send claims, scoped remote observations, retained MR
  identifiers, residual races, revisions and durable callbacks.
- Admission validates trusted authorization and candidate preparation
  before creating a new operation.
- A committed send claim never independently grants write authority.
  Claimed sends are not automatically repeated after an unknown response.
- `reconcileOnly` uses an existing exact request identity and a scoped
  reader; it does not call an external GitLab writer. Local ledger
  observations may be updated.
- Unclaimed collision states are sticky at both Java reconciliation and
  PostgreSQL trigger boundaries, with regression tests for push, Draft
  MR and direct SQL rearm attempts.

### Verification evidence

- Disposable PostgreSQL: 112/112 tests PASS, including 8 schema/ACL,
  11 durable-ledger, 18 coordinator, 14 synthetic-fault-flow,
  54 VN-T13 PostgreSQL and 7 contract tests.
- Five VN-T13 concurrent replay repetitions and three VN-T14 CAS
  repetitions PASS.
- Flyway V1-V4 migrate/repeat/validate and owned cleanup PASS.
- Java 21 offline `clean verify`: 971 discovered, 865 executed,
  zero failures/errors, 106 expected opt-in skips; BUILD SUCCESS.
- No live GitLab push, Draft MR, merge, approval, remote fault
  injection or credential revocation was performed by these checks.

### Outstanding boundaries

Current safe-CI enrollment, trusted Owner grant and independent receipt
issuance, isolated live writer credentials, real GitLab mutation transport,
trusted caller authorization for recovery, credential lifecycle, and live
fault/revocation acceptance remain unverified or unavailable.

The GET-only transport and local credential helper do not by themselves
prove project-scoped live authorization, complete filesystem race
protection or isolated production credential handling.

No local verification result is a substitute for Owner acceptance,
authorization to mutate GitLab, or an end-to-end acceptance result.
