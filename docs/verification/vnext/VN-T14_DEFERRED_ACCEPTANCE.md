# VN-T14 — live acceptance DEFERRED

Recorded 2026-11-03 after the Owner stopped further live preparation and requested
repository cleanup before VN-T15. This is a handoff, not a ticket status update,
new authorization, enrollment permit or completion claim.

## Verified local work and its limits

The Owner reported a successful final run of the protected runtime verifier:
**139 local checks**, including six actual PostgreSQL verify-full TLS logins,
unchanged orchestration V1–V13 / Java V1–V7 histories and role separation, installed
Java checks under service identities, active Go authority identity/artifact,
authenticated routes, mTLS rejection cases and actual agent isolation denials.
These are recorded historical results; cleanup did not rerun provisioning or
restart the authority. The expired-client-certificate fault was explicitly
**not verified**.

The dedicated reader/writer credentials were imported privately and their
GET-only bot/Developer/scope/expiry/project/protected-main checks passed. This
proves only those checks at collection time. Developer403s and incomplete
pagination remained INCOMPLETE. Owner-session inspection found no selected
root/custom CI config, Auto DevOps off, and empty applicable effective policy/
compliance/schedule connections. Those multi-page historical observations are
not fresh operation-bound enrollment. No CI pipeline success is inferred.

The protected publication/request-import tooling was exercised with disposable
fixtures and real service-UID access checks. Earlier timed rehearsals include an
over-budget result and failed personal review attempts. The final retained failure
was a challenge mismatch immediately after prompting, not expiry; the input
bytes were not logged. The dummy CLI received a targeted queued-input flush fix.
**No complete successful human-in-the-loop timed rehearsal is claimed.** Failed
records, timestamps and source observations remain local and unchanged.

## State at cleanup

Read-only checks during cleanup observed both installed `liveWritesEnabled`
flags **false**, no running preparation helper, and zero rows in the isolated
VN-T14 policy, controlled-candidate-evidence, delivery-authorization, Owner-grant
and Java-operation tables. No real candidate, approval or bounded delivery attempt
was started in this sequence. There is no VN-T14 MR link or independently observed
post-delivery CI result to report. Existing VN-T13 behavior is untouched.

The selected future scope remains project **87786140**,
`qhung-13/asep-m01-sandbox`, source `test/vn-t14/<actual-operation-id>` → protected
`main`, fixed probe file only, one Draft MR; no force/main push, merge, GitLab
approval or compensating delete. Any future execution must be explicitly resumed
and must retain personal exact-request approval and every current runtime gate.

## Deferred acceptance / recovery matrix

| Subject | State / next requirement |
| --- | --- |
| Local runtime | Historically verified as above; revalidate changed/expired prerequisites when execution is resumed, without blindly reprovisioning |
| Real exact candidate | Not prepared; obtain the actual controller seal and independent verifier receipt, then review the exact objects, single-file diff and canonical request |
| Current enrollment | Pending; independently assess all18 required subjects with genuine fresh provenance and five complete inventories; never promote403/missing metadata or old observations to VERIFIED |
| Timing and personal approval | Pending; no end-to-end timed success; fit collection, independent assessment, protected publication/import, personal hash challenge and delivery into the existing300s windows. Do not extend/refresh them silently |
| Real branch push / Draft MR / ledgers | Not observed; independently confirm remote SHA, MR identity/draft/open state, both Java ledger steps and authenticated Go outcome after a separately resumed bounded attempt |
| CI | No post-write event observed; report any actual pipeline result separately from MR creation; do not build CI/CD to satisfy this probe |
| Live fault acceptance | Response loss/UNKNOWN recovery, writer revocation after confirmed push, source/target drift and expired-client-certificate cases remain unobserved in the real target environment; local fixtures do not complete these cases |
| Credentials | Existing scoped files remain protected outside Git; original token reported revoked, replacement credentials were checked with Nov4 2026 expiry. Revalidate actual expiry/state before future use; no renewal, recovery, rotation or revocation is authorized by this handoff |

If any future send is UNKNOWN, preserve the original operation, ledger and remote
facts; use read-only reconciliation before considering any further mutation. Never
blindly resend, replace the operation to hide uncertainty, delete remote effects
or claim rollback. Restore the executor write flag to false after an authorized
attempt while preserving reader/recovery access. Controller write flag stays false.

## Reviewable repository boundary

[Maintained tooling](../../../deploy/delivery/vn-t14/README.md) includes source,
fixture tests, pin manifests, policy-reference JSON and read-only query source.
The original dated bundle is retained in place and ignored, preserving its path-
sensitive scripts, manifests, raw observations, generated artifacts and unfinished
execution driver. See its complete [classification](../../../deploy/delivery/vn-t14/CLASSIFICATION.md).
No installed file, service, database, credential or remote project was altered by
cleanup. The [cleanup validation](VN-T14_CLEANUP_VALIDATION_2026-11-03.md) records
only commands actually run for this repository change.

Proposed review groups, with nothing staged or committed:

1. VN-T14 tooling/handoff: `.gitignore`, `deploy/delivery/vn-t14/`, the delivery
   README link, this handoff and its cleanup validation.
2. K3s foundation proposal: existing `deploy/k3s/`, ADR-VN-05 and the existing
   `docs/INDEX.md` diff, reviewed separately; no K3s activation or VN-T14 dependency.
3. Unrelated `specs/`: preserve unchanged and unstaged; excluded from both groups.
