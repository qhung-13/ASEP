# Sandbox delivery authority — implementation and unresolved deployment

Engineering evidence, 2026-10-29. This is not an approved architecture change,
operator enrollment, Safe-CI enrollment or live acceptance record. Project
87786140 (`qhung-13/asep-m01-sandbox`), source `test/vn-t14/<operation-id>`,
protected `main`, Draft MR only remain the fixed scope.

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
