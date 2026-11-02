# VN-T14 repository cleanup validation — 2026-11-03

Scope: repository source/document classification and an ignored local archive.
No host installation, service, credential, policy, candidate, grant or GitLab
write was performed. No stage, commit or push. Live acceptance is **DEFERRED**.

## Actual focused commands and results

From the repository root:

```bash
python3 -B deploy/delivery/vn-t14/build-readonly-preflight.py
python3 -B deploy/delivery/vn-t14/test-protected-publication.py
python3 -B deploy/delivery/vn-t14/test-publication-java.py
```

All exited0. Packaging reproduced SHA256
`bf07cacfdbf181bafb0d1f5a95bf9df6ad5ff93e8dba64961f2f84dc09f5a2bf` from the unchanged
Java preflight source. Publication/import/installer/hidden-entry fixtures: **26
tests, OK**. Java compatibility: canonical request hash,18-subject enrollment,
five dummy counts and packaged invalid-mode rejection before credential/network
use all passed. The tests ran as the real unprivileged Owner UID1000 for meaningful
POSIX ownership checks, never root; all fixture destinations were disposable.

```bash
(cd deploy/delivery/vn-t14 && sha256sum --check REVIEW-TOOLS.sha256)
```

Exit0, all4 inputs OK. The default sandbox emitted three stream-fd warnings;
the checksum command still reported all four matching pins. Installed inputs
were not changed. `SOURCE.sha256` separately records the copied versioned
source/query/profile hashes, excluding generated JARs.

The actual read-only isolated-database status query was:

```bash
docker exec asep-vnt14-pg-test psql -X -U postgres -d asep -At -c "SELECT 'policies='||count(*) FROM orchestration.delivery_policies UNION ALL SELECT 'candidate_evidence='||count(*) FROM orchestration.controlled_candidate_evidence UNION ALL SELECT 'delivery_authorizations='||count(*) FROM orchestration.delivery_authorizations UNION ALL SELECT 'owner_grants='||count(*) FROM code_delivery.owner_grants UNION ALL SELECT 'java_operations='||count(*) FROM code_delivery.vn_t14_operations;"
```

The default sandbox first denied Docker-socket access (exit1). The identical
read-only query under host UID1000 succeeded (exit0): all five counts0. This was
inspection only; development DB55432 and isolated DB55433 were not modified.

## Preservation, paths and review audit

The original18-entry plan manifest,4-entry review-tool manifest and20 migration
hashes matched before classification. Originals remain at exactly the same paths;
no pin was updated to accommodate a moved source. All11 copied source/query/
profile files are byte-identical. Keeping `deploy/delivery/vn-t14/` at the same
repository depth preserves the builder's root calculation; sibling imports and
installer input lookup were exercised by the focused tests above.

The original archive, unrelated `specs/`, K3s files, ADR and pre-existing Index edit
were hashed before cleanup into ignored `tmp/vn-t14-review/before.json` for final
comparison. The active installed unit and launchers had no checkout-bundle path
reference; no preparation helper was running. Both installed write flags were
read as false. No installed tooling was relocated, rewritten or restarted.

A local review audit scans the complete proposed VN-T14 and separate K3s file
sets without printing matching content. It checks forbidden key/kubeconfig/build
filenames, private-key blocks, token formats, authenticated HTTP URLs and embedded
kube credential data, plus Python parsing, links, whitespace, ignores, pin copies
and preservation hashes. This is a bounded source scan, not proof against every
possible secret format. An initial link audit caught the not-yet-created validation
file; this report supplies that target. Final results are recorded below.

The complete proposed patch, including unstaged new files, is retained locally at
`tmp/vn-t14-review/proposed.patch`; source inventory and audit implementation are
beside it. These local review artifacts are ignored. No raw observation, secret,
generated JAR/class/cache, concrete local runtime config or `specs/` file belongs
to the VN-T14 proposed patch.

Full Java/Go/PostgreSQL regression suites and the139-check host verifier were not
rerun: no application code, migration or installed runtime changed. Their previous
results are historical evidence, not new cleanup test results. Remote delivery,
CI and fault acceptance were not exercised.

## Final cleanup audit results

```bash
(cd deploy/delivery/vn-t14 && sha256sum --check SOURCE.sha256)
python3 -B tmp/vn-t14-review/review-cleanup.py
git diff --check
git status --short --branch
git diff --cached --stat
```

Source-manifest verification: all10 entries OK, exit0. Local review audit: exit0;
all original plan/tool/migration pins and maintained manifests matched; seven
Python sources parsed;11 copied files remained byte-identical; source/query links
and new VN-T14 whitespace passed;43 proposed VN-T14/K3s files passed the bounded
secret/artifact scan;101 original archive/specs/K3s/ADR/Index files and preserved
file inventories were unchanged. Archive/build ignores and source visibility
passed. All four installed review-tool pins still matched and both installed
live-write flags remained false.

`git diff --check` passed, exit0. A separate full new-file `git diff --no-index
--check /dev/null <file>` audit found one **pre-existing K3s-group** warning:
`docs/architecture/adr/vnext/ADR-VN-05-HYBRID-K3S-DEPLOYMENT.md:3`, the two-space
Markdown hard break after the Date field. It is retained unchanged for separate
review. The first audit wrapper incorrectly treated the ordinary no-index
"files differ" status as failure; the corrected wrapper examined diagnostics
and distinguished the unrelated warning. No VN-T14 whitespace finding remains.

Final review groups:19 VN-T14 files and24 existing K3s/ADR/Index files; unrelated
`specs/` excluded. Staging remained empty. The branch remained ahead13, with no
commit or push performed. No additional runtime tests or remote fault drills ran.

## Final local-commit review — 2026-11-03

The Owner subsequently authorized two focused **local commits only**, with no
push or host operation. The19-path VN-T14 set is the maintained tooling directory,
root archive ignore rule, delivery README link and two handoff/validation docs.
No dated bundle, real config, capture, evidence/permit, experimental driver,
generated JAR/class/cache, temporary patch or `specs/` content is included.
An unrelated working-tree `/specs` ignore rule is deliberately excluded from
staging and remains local.

To check dependency closure, the maintained VN-T14 source files and existing Java
preflight source were copied into a fresh temporary repository-shaped tree, with
no dated/trial directory and no prebuilt JAR. These commands ran there:

```bash
python3 -B deploy/delivery/vn-t14/build-readonly-preflight.py
python3 -B deploy/delivery/vn-t14/test-protected-publication.py
python3 -B deploy/delivery/vn-t14/test-publication-java.py
```

All exited0: identical preflight JAR hash,26 tests OK and both Java compatibility
checks passed. The explicit external dependency remains the documented pinned,
public installed application JAR/Java21 toolchain, not ignored trial material.
No application code or receipt/gate behavior changed. Live acceptance remains
**DEFERRED**. K3s example conversion and its offline checks belong to the separate
K3s/ADR/Index commit; they provide no VN-T14 runtime or acceptance evidence.
