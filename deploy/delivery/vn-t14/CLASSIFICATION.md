# Classification of the 2026-11-02 local bundle

2026-11-03 repository cleanup only. Every original file is retained unchanged
under the now-ignored `deploy/delivery/prepared-2026-11-02/`. No installed path
or old manifest was moved or rewritten. The categories below cover all original
files, including ignored caches; copied source has an additional versioned home.

| Category | Original files | Review destination / reason |
| --- | --- | --- |
| Reusable scoped source and tests | `protected_local_files.py`, `publish-delivery-evidence.py`, `import-gitlab-credentials.py`, `install-review-tools.py`, `build-readonly-preflight.py`, `test-protected-publication.py`, `test-publication-java.py`, `REVIEW-TOOLS.sha256`, `safe-ci-policy-definition.json` | Byte-identical copies beside this document; fixed-profile limitations are explicit in README |
| Reusable read-only query source | `owner-policy-baseline.graphql`, `owner-event-baseline.graphql` | Byte-identical `queries/effective-policies.graphql`, `queries/event-configuration.graphql`; responses excluded |
| Useful history, summarized for review | `BOUNDED-PROBE-ENROLLMENT-REVIEW.md`, `COORDINATED-EXECUTION-CHECKLIST.md`, `CREDENTIAL-BATCH-EXECUTION.md`, `GITLAB-READONLY-INSPECTION-2026-11-02.md`, `LOCAL-RUNTIME-EXECUTION.md`, `OWNER-RUNBOOK.md`, `PUBLICATION-AND-CREDENTIAL-BATCH.md`, `PUBLICATION-VALIDATION.md`, `TIMED-REHEARSAL-2026-11-02.md`, `VALIDATION.md` | Originals remain local; concise durable state, evidence limits and recovery constraints are in the deferred handoff |
| Concrete host configuration and pinned local inputs | `candidate-controller.json`, `delivery-executor.json`, `delivery-authority.json`, `controller-job.json`, `inventory.json`, `owner-approval.properties`, `profile-definition.json`, `asep-delivery-authority.service`, `orchestration.flyway.conf.example`, `delivery.flyway.conf.example`, `base-objects/**`, `bootstrap-roles.sql`, `seed-policy.sql`, `MANIFEST.sha256`, `RUNTIME-MIGRATIONS.sha256` | Local only; deployment templates already exist one level above, without promoting concrete UIDs/artifact pins/operation IDs into new generic templates |
| Host-bound provisioning and diagnostics | `apply-local-host.py`, `finish-local-runtime.sh`, `resume-local-runtime.sh`, `validate-plan.py`, `verify-local-runtime.py`, `check-agent-access.py`, `check-publication-service-roles.py`, `import-db-passwords.py`, `test-import-db-passwords.py`, `verify-credential-setup.py`, `test-credential-setup-driver.py` | Local only, as intact dependent pairs/bundle; hard-coded identities, isolated DB, installed artifact or privileged host context prevent treating them as portable deployment tools |
| Experimental orchestration and rehearsal source | `execute-bounded-delivery.py`, `test-bounded-delivery-driver.py`, `timed-publication-rehearsal.py`, `test-timed-rehearsal-java.py`, `dummy-owner-review.py`, `test-dummy-owner-review.py`, `DummyOwnerReview.java`, `build-dummy-review.py`, `owner-ui-rehearsal-collector.js`, `test-owner-ui-routes.mjs` | Local only; retain failed results and implementation experiments without presenting the unfinished real-operation driver or timed rehearsal as an accepted runtime procedure |
| Raw authenticated observations | `owner-policy-baseline-result.json`, `owner-event-baseline-result.json` | Local only; never converted into fresh Safe-CI evidence or included in the proposed patch |
| Generated output and caches | `readonly-preflight.jar`, `dummy-owner-review.jar`, `__pycache__/**` | Local only; maintained packaging output is also ignored beside its source |

Existing protected credentials/keys and `/tmp/asep-vnt14-*` observations remain
at their original locations outside the proposed versioned set. No secret was
copied, displayed or regenerated. Failed rehearsal records are preserved;
cleanup neither refreshes their times nor upgrades their outcome.
