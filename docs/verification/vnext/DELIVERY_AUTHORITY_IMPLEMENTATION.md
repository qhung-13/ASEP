# Sandbox delivery authority — implementation and unresolved deployment

Engineering evidence, 2026-10-29. This is not an approved architecture change,
operator enrollment, Safe-CI enrollment or live acceptance record. Project
87786140 (`qhung-13/asep-m01-sandbox`), source `test/vn-t14/<operation-id>`,
protected `main`, Draft MR only remain the fixed scope.

> Continuation, 2026-10-30: the repository now includes the probe controller,
> offline-tested Git smart-HTTP/Draft MR adapter, authenticated intent/outcome
> contracts and protected runtime/deployment templates. See
> [remaining local implementation report](VN-T14_REMAINING_LOCAL_IMPLEMENTATION_2026-10-30.md)
> and [deployment preparation](../../../deploy/delivery/README.md).
> The operator consumes the controller's indexed atomic batch; the manual receipt
> layout described below is historical preparation guidance. Later sections listing
> missing writer/runtime code describe the 2026-10-29 checkpoint. Protected host
> deployment, complete Safe-CI enrollment, credentials and live acceptance remain
> independently blocked; this continuation does not approve an ADR or enroll a host.

> Owner priority, 2026-10-31: complete all safely implementable VN-T14 code and
> local verification before Safe-CI enrollment or protected deployment verification.
> The [current local assessment](VN-T14_LOCAL_COMPLETENESS_2026-10-31.md) separates
> repository completeness from real acceptance. VN-T14 is the only active ticket.

## Restricted human operator

`OwnerApprovalCommand` is an explicit Java 21 command; it is not a Spring bean,
HTTP endpoint or automatic delivery worker. `OwnerGrantAuthority` uses a separate
issuer connection. V6 derives grant/audit actors from the authenticated database
session, overwriting caller labels. Issue/revoke and audit insertion are one
transaction. Same-operation replay retains the original grant and expiration;
revoked/expired grants cannot be renewed. Commit response loss is an uncertain
outcome: inspect the exact original operation, do not invent a new grant.

The following host setup requires explicit Owner authorization and has **not**
been performed by the implementation agent:

- A separate non-root operator OS account, distinct from the agent; protected
  controller account(s) distinct from the agent; no shared supplementary group,
  ptrace, sudo, filesystem, secret or service-control capability for the agent.
- A reviewed JAR installed root-owned at `/opt/asep/owner-approval/asep.jar`, and
  `backend/scripts/owner-approval` installed root-owned as
  `/usr/local/sbin/asep-owner-approval`. All ancestors must be non-writable by
  other users. Execute only the installed artifact, never agent checkout code.
- Root-owned `/etc/asep/owner-approval.properties` with exactly the keys below.
  Pin the reviewed installed JAR's SHA-256. No path override is accepted.
- A dedicated per-human PostgreSQL login inheriting only `asep_delivery_issuer`,
  with no superuser, role creation, database creation or RLS bypass. Its password
  file is operator-owned mode 0600 under protected ancestors; it must never be
  supplied to the delivery worker or agent. Use PostgreSQL TLS `verify-full` and
  a root-controlled CA with IP SAN for 127.0.0.1. No production credentials are
  provisioned by migrations or fixtures.
- Controller-owned immutable object/receipt storage. Populate objects under
  `objects/{commit,tree,blob}/{sha1}` and strict JSON receipts under
  `seals/{uuid}.json`, `verifications/{uuid}.json`, using the preparation record
  schemas. The controller must genuinely stop writers, seal exact Git objects
  and run the independently protected verification profile; copying an agent
  receipt or fixture PASS here does not establish trust.

Configuration keys (values must be supplied by the trusted host operator):

```
operator_uid, agent_uid, database_login, database_url, password_file, database_ca,
evidence_root, evidence_uid, profile_sha256, harness_sha256, image_sha256,
minimum_tests, maximum_receipt_age_seconds, installed_jar_sha256
```

`database_url` accepts only `jdbc:postgresql://127.0.0.1:<port>/<database>`;
credentials are separate and are never accepted in flags/URLs/environment.
A human must import the proposed request JSON into a private operator-owned
review inbox under protected ancestors. Then, in their actual terminal, run
`asep-owner-approval approve <private-request.json> <seconds:1..3600>` or
`asep-owner-approval revoke <private-request.json> <seconds:1..3600>`. The human
reviews exact scope, candidate, revisions, evidence references and the exact
single-file probe contents, then types the full request-hash challenge. Approval
rehashes the exact objects and checks independently issued receipts before and
after review. Revocation stays available when verification receipts expire.
Kernel UIDs, root-owned installation and database principal are checked before
issuer use; `user.name`, caller actor labels and an agent's CLI confirmation are
not authority. The launcher clears inherited JVM injection environment variables.

Storage ownership checks alone do not prove controller issuance or host isolation.
The real operator, protected installation, controller storage, database TLS and
issuer isolation must be demonstrated separately. Until then this slice is
IMPLEMENTED/locally tested and operationally BLOCKED.

## Go-owned current delivery eligibility

V11 adds `delivery_policies`, `delivery_review_evidence` and immutable
`delivery_authorizations` in the Go schema. V1–V10 remain unchanged. A separately
protected `asep_orch_authority` role can issue exact review records and update
current policies/revoke reviews; it cannot write workflow state. Runtime cannot
issue reviews or policy. PostgreSQL row-share locks require column UPDATE rights;
only immutable identity columns have that right, and triggers reject every actual
runtime UPDATE. Tests must verify this subtle ACL boundary on PostgreSQL.

`AuthorizeDelivery(evidenceId)` locks run, policy, protected review and review task;
checks current REVIEWING version, exact producer attempt/candidate/request codec,
policy epoch/scope, independently issued review, valid claimant/lease and DB-time
expiry after lock waits. One transaction completes review task/attempt, advances
run to DELIVERY_PENDING, creates a distinct delivery attempt, appends transition
and outbox event and persists the original operation identity. Concurrent requests
cannot authorize two candidates. An exact replay reads the original identity;
it does not refresh evidence expiry. Unknown COMMIT never returns approval. A
current read by original operation/run/producer can recover the durable outcome.
No Java table or cross-schema write participates in this transaction.

`CancelDelivery` invalidates REVIEWING/DELIVERY_PENDING eligibility under the same
run lock, increments run version and task fencing and records a typed stop intent
and outbox event. Its run/version stop-intent ID is deterministic; replay preserves
the original audit/outbox/fencing result after a lost acknowledgement. It does
not assert process cleanup, rollback or absence of a
GitLab effect. Existing legacy cancellation constraints are preserved, with an
additive DELIVERY_CANCEL case. Review issuance, cancellation and policy control
must only be called by a protected controller, never by an agent-facing API.

The exact authority read contract is:

```
GET /internal/v1/delivery-authority/{operationId}/{runId}/{producerAttemptId}
```

The separate handler requires a verified TLS 1.3 client chain with exactly
`spiffe://asep.local/code-delivery` as URI SAN. `TLSConfig` requires a dedicated
client trust bundle. There is no bearer/header identity fallback or write endpoint.
It is not added to the synthetic development server. A protected host must supply
a dedicated listener, server key and service trust roots, with bounded HTTP
server timeouts. There is **no enabled listener or deployed PKI** in this change.

`GoDeliveryAuthoritySource` is the matching explicit Java HTTPS reader, constrained
to a protected loopback origin, TLS 1.3 and pinned server certificate, with a client
identity supplied by protected SSLContext. Redirects/proxies are disabled, JSON
is bounded/strict and the deadline covers the entire response. Missing, failed or
mismatched observations deny authorization. Go timestamps observation before the
DB read; Java additionally rejects expired `validUntil` (minimum of policy/review
expiry), future/stale observations, and any operation/full-request hash mismatch.
The existing composite consumes this source alongside `JdbcOwnerGrantSource`;
it still proves only OWNER_WRITE_AUTHORIZATION and CURRENT_POLICY. No successful
default or claim that other delivery gates passed was added.

An internal authenticated read API retains Go state ownership and requires no
Java access to orchestration tables. A direct SQL view would duplicate current
policy logic and introduce cross-owner DB privileges; the synthetic bearer API
cannot establish production authority. mTLS is an implemented engineering option
for this sandbox, not a change to ADR-VN-04's OPEN deployment/identity decision.
Owner approval and protected service provisioning remain necessary before use.

**Still absent:** real upstream stage progression into REVIEWING, a protected
review/policy issuer connected to independently sealed and verified candidates,
an authenticated transition/cancellation controller, delivery outbox dispatcher
and outcome callback wiring. PostgreSQL fixtures deliberately seed an upstream
review stage; they do not demonstrate these missing components. The new API is a
current eligibility read, not an atomic fence across PostgreSQL and GitLab.
Revalidate at the coordinator's mutation boundary; an accepted remote effect after
a concurrent cancellation must remain observable and reconciled, never erased.

## Owner evidence and credential setup proposal — no actions performed

The deleted GitLab credential is unavailable. Do not search for it, recover it,
reuse its old path or treat a token ID as a recoverable secret. Its server-side
status is only **last known active**, from the Owner's earlier report. No token
was created, rotated, recovered or revoked during this work.

The least additional credential exposure is an Owner-operated read-only UI
review, recording project identity, namespace kind/ancestors, exact revision,
current time, observed account/role, all inventory pages and explicit visibility
limitations. Review project/group variables **metadata only**, runners and runner
scope/protection/behavior, CI config path and complete transitive includes at the
candidate's exact base, inherited/group/security/compliance policies, Auto DevOps,
protected branches/resources/environments, hooks/integrations, deploy keys/tokens,
job-token/deployment capabilities and both push/MR pipeline behavior. Record N/A
only when absence is established; an invisible setting, 403, missing page or
missing pagination metadata stays UNKNOWN. Public repository contents or an
empty `.gitlab-ci.yml` observation cannot establish this complete inventory.

A separately approved, short-lived project-scoped **read_api** credential at the
minimum role needed for those endpoints is an alternative for a protected,
redacting inspection harness. Never elevate the Developer delivery token. A
project token does not establish parent-group or instance visibility. Separate
Owner/group/provider evidence may still be necessary; Maintainer/read_api is not
a universal proof of runner isolation or safety. Some variable APIs return secret
values: the trusted harness must discard values before any report/log/model
boundary. Do not add credential creation to this read-only investigation.

These limits follow GitLab's official [project-token scope documentation](https://docs.gitlab.com/user/project/settings/project_access_tokens/),
[variables API](https://docs.gitlab.com/api/project_level_variables/),
[runner configuration](https://docs.gitlab.com/ci/runners/configure_runners/) and
[pipeline execution policies](https://docs.gitlab.com/user/application_security/policies/pipeline_execution_policies/).
Policy pipelines can inject or replace project configuration; project-local UI
observations alone cannot rule that out. Runner/protected-variable access also
requires evaluating the actual project and MR pipeline configuration.

At inspection this turn, the available browser showed an unauthenticated public
project view (sign-in required), project 87786140 and main commit
`cd0b93035954142346b7cba18f29657ee877163b`. This corroborates public identity/head
only; it does not refresh protected settings, grant issuer trust or Safe-CI.
No GitLab mutation or authentication attempt was performed.

After independent evidence closes Safe-CI and host isolation, the Owner can
separately authorize credential provisioning: retire/revoke the old unrecoverable
server token through the Owner account, then supply a fresh short-lived dedicated
Developer credential directly to the protected Java executor (private file/secret
mount, outside repository and child sandboxes). The write scope remains only this
project, deterministic source and Draft MR targeting protected main. Validate
identity/scopes/expiry and deny main-push capability immediately before use.
The credential must never be placed in Git URLs/config, logs, model input or
candidate/verifier environments.

Use an explicitly approved staged revocation case: authorize an exact test
operation, observe the permitted branch effect if any, revoke the designated test
credential through the Owner, then demonstrate further mutations fail closed.
Retain actual branch/MR identity and UNKNOWN/race state; use separately authorized
read-only Owner observation for reconciliation when the revoked credential can no
longer read. Never infer rollback, obtain a broader fallback or retry an uncertain
write. Remove local secret material and verify final server-side revocation after
the test. This is a proposed lifecycle; none of these credential actions occurred.

## Revised blocker matrix

| Condition | Current evidence | Remaining prerequisite |
|---|---|---|
| Grant storage/issuer | IMPLEMENTED; real disposable PostgreSQL exercised | Protected operator account, reviewed root-owned installation, separate issuer login/TLS; actual human approval NOT VERIFIED |
| Candidate/evidence validation | Hash/profile checks and protected reader IMPLEMENTED/tested | Actual stopped writer, immutable sealed Git objects and independent protected verifier issuance; fixture receipts are ineligible |
| Go delivery lifecycle | Transaction, current policy read, cancellation/replay IMPLEMENTED; disposable PostgreSQL tested | Real upstream REVIEWING progression and protected review/policy issuer/controller; no fixture seeding in live use |
| Java–Go authority | mTLS contract and Java reader IMPLEMENTED; loopback TLS tested | Owner-selected authenticated deployment, protected keys/trust roots/listener, actual service wiring; no enabled authority listener |
| Complete coordinator gates | Existing coordinator/ledger and composite retained; negative gates tested | Actual evidence issuers, authenticated outbox/callback wiring and all gate requirements; no all-verified default |
| Safe-CI enrollment | BLOCKED / NOT VERIFIED; earlier Developer 403s and partial Owner observations | Current complete Owner-visible project/inherited/policy/runner/deployment evidence; no missing observation promoted to safety |
| GitLab credential | UNAVAILABLE; old local file deleted; server token last known active | Owner-approved lifecycle above, only after other prerequisites; no credential recovery |
| GitLab write transport | NOT IMPLEMENTED / NOT ENABLED | Part E explicitly requires verified security conditions before implementing actual writes |
| Real acceptance/faults | NOT RUN | All preceding prerequisites, exact reviewed candidate SHA and separate exact-operation Owner approval before first mutation |

The next Owner decision is whether to approve the isolated operator/service setup
and mTLS sandbox deployment described here, and provide complete Owner-operated
read-only Safe-CI evidence. This does not authorize a token, settings change,
remote write, push of the ASEP branch or merge. Once prerequisite evidence exists,
prepare the exact project/source/target/SHA, expected effects, CI implications and
reconciliation options for the separately required live-operation approval.
