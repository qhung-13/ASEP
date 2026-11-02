# VN-T14 protected review tooling

Maintained source extracted from the local `prepared-2026-11-02` bundle.
**Live acceptance is DEFERRED.** This directory is not enrollment evidence,
Owner approval, a host installer authorization or a generic deployment profile.
See the [deferred-acceptance handoff](../../../docs/verification/vnext/VN-T14_DEFERRED_ACCEPTANCE.md).
The existing [deployment templates](../README.md) remain the deployment reference.

## Contents and scope

- `protected_local_files.py`: bounded fd-relative reads, safe directory traversal,
  exclusive publication, restrictive modes and no-overwrite checks.
- `publish-delivery-evidence.py`: inspect/import the exact indexed request; stage
  explicit independently assessed subjects; publish a complete protected permit
  last. It never issues verification, an assessment or a human delivery grant.
- `import-gitlab-credentials.py`: private terminal double entry of already
  authorized, distinct credentials; no token administration or network requests.
- `install-review-tools.py`: checks four pinned inputs, defaults to check-only,
  and refuses existing installation/staging destinations. Do not run it against
  the already-installed host as part of repository cleanup.
- `build-readonly-preflight.py`: offline deterministic packaging of the existing
  Java GET-only test harness, without changing application source or rebuilding
  the application JAR. The generated JAR is ignored.
- `test-protected-publication.py` and `test-publication-java.py`: disposable,
  unprivileged fixtures and Java codec/enrollment compatibility checks. Their
  synthetic facts and credentials must never enter live evidence paths.
- `safe-ci-policy-definition.json`: the bounded18-subject policy reference;
  its hash alone proves no project's safety. `queries/` contains read-only
  Owner-session query source, without any authenticated responses or captures.

These are deliberately **VN-T14 fixed-profile tools**, copied byte-for-byte.
They retain project87786140, the reserved operation/grant IDs, policy/base/profile
bindings, reviewed numeric service identities and the original Nov4 credential
expiry check. Those constants are not portable defaults or fresh authorization.
Before any future use, independently review their applicability and the actual
host/runtime/credential state. Do not substitute new IDs, dates or pins merely to
make a check pass. Parameterizing the profile is future work, not this cleanup.

## Offline checks

Run from the repository root as an ordinary user, never root:

```bash
python3 -B deploy/delivery/vn-t14/build-readonly-preflight.py
python3 -B deploy/delivery/vn-t14/test-protected-publication.py
python3 -B deploy/delivery/vn-t14/test-publication-java.py
(cd deploy/delivery/vn-t14 && sha256sum --check REVIEW-TOOLS.sha256)
```

The builder and Java compatibility check require Java21 at the reviewed path,
read access to the existing pinned `/opt/asep/delivery/asep-backend.jar`, and the
matching repository Java preflight source. They fail on a different artifact;
they do not install or update it. The source directory stays at the same depth
below the repository root so the builder's `BASE.parents[2]` remains correct.
The publication tests import sibling modules and need the generated preflight
JAR for installer fixtures, so build it first and use `python3 -B`, not `-I`, for
these test scripts. Installed entry points explicitly support isolated Python.

`REVIEW-TOOLS.sha256` preserves the four installed-input pins, including the
reproducible generated JAR. `SOURCE.sha256` covers the versioned source/query/
profile files. Neither manifest is an enrollment or acceptance receipt.

No check above reads real credentials, calls GitLab/DB, publishes live evidence,
issues a grant, changes settings, activates services or enables writes.

## Local history and review groups

The original `deploy/delivery/prepared-2026-11-02/` is ignored and retained in
place. This keeps every local path/import, old manifest, failed rehearsal and
machine-specific runbook usable for investigation without altering installed
`/opt`, `/etc` or `/var/lib` files. Do not force-add that directory. The original
bundle is a local archive, not a reconstruction source for fresh evidence.
Concrete host configs, base Git objects, migration/provisioning batches, token
metadata/reports, raw observations, one-off runtime/rehearsal drivers, unfinished
live orchestration, generated JARs/classes/caches and temporary material stay
there or in their existing protected/local temporary locations.

The complete classification is in [CLASSIFICATION.md](CLASSIFICATION.md).
Keep these sources, the delivery README link and the deferred handoff in the
VN-T14 review group. K3s files, ADR-VN-05 and the pre-existing `docs/INDEX.md`
diff form a separate group. Unrelated `specs/` is excluded and remains unstaged.
