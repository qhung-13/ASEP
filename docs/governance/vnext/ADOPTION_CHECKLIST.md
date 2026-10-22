# ASEP vNext — Documentation Adoption & Repository Safety

**Prepared:** 2026-10-22
**Status:** OWNER-DIRECTED DOCS UPDATE PREPARED — not yet applied or committed in the user's Ubuntu Git working tree.
**Approval source:** Owner selected Go + PostgreSQL minimal orchestration, Python/OpenHands Agent Execution, and accepted P-01/P-02/P-03. On 2026-10-22 Owner instructed the assistant to update docs according to those choices without reopening the architecture research.

## 1. Exactly what the decision means

- **P-01:** Next execution priority is an evidence-gated Jira-to-verified-GitLab-Draft-MR vertical slice. Existing M01 status and approved review/publication rules are not automatically cancelled, completed or erased.
- **P-02:** Internal stages may advance automatically **only under an explicit Owner-scoped, default-deny policy** and independent gates. Human alone reviews/merges. GitLab comment Auto-Publish remains a distinct, default-off M01 feature; branch/Draft MR Code Delivery requires a separate scoped privileged executor.
- **P-03:** Inference stays local-only; Claude/cloud escalation and external telemetry remain out of scope until a separately approved change.
- **Technology:** Retain Next.js/Java Spring Boot/Keycloak in Application; select Go + PostgreSQL custom durable queue for Orchestration; select Python + OpenHands SDK behind an ASEP-owned adapter for Agent Execution. One PostgreSQL instance with domain-owned schemas, internal REST/JSON initially, no new queue broker or MariaDB.

These are **design-level decisions**. No local model, OpenHands agent, sandbox, credentials, GitLab write or recovery mechanism is marked VERIFIED.

## 2. Apply only additive vNext docs; do not replace existing histories

The **corrected documentation patch** adds Markdown files only under:

- `docs/governance/vnext/`
- `docs/requirements/vnext/`
- `docs/architecture/vnext/`
- `docs/architecture/adr/vnext/`
- `docs/verification/vnext/`

The supplied Git patch also inserts marked navigation/decision sections into:

- `README.md`
- `docs/INDEX.md`
- `docs/governance/DECISIONS.md`

**Must remain untouched:** historical M00/M01 sources and recovered notes, effective existing M01 requirements, ADR-001/002, publication ledger policy, old roadmap snapshot and all completed/paused ticket evidence.

## 3. Live repository verification

Before committing, inspect `git status -sb`, branch/HEAD, root path and existing files. **The old `apply_vnext_docs.py` utility is withdrawn** after independent review F01–F03; the corrected ZIP contains **no executable installer**. Only use `git apply --index --check` and, if the current repository matches, `git apply --index` on a dedicated documentation branch. Git is not a multi-file filesystem transaction: keep a recoverable branch/commit and inspect resulting staged changes. If patch checks fail, stop; do not force it or overwrite current files.

**Mandatory safe-application order:** review the ZIP-root `README_APPLY.md` (not copied into the repository), verify its SHA manifest, inspect the actual repo/HEAD/index, and use its **single symlink-guarded command block**. A standalone `git apply --index --check` does **not** detect every symlinked parent directory: a controlled test confirmed a patch could follow an untracked `docs/architecture` symlink outside the checkout. Stop on *any* symlink under `docs`, a symlinked `README.md`, failed `find`, index conflict, or existing vNext path; do not bypass the guard.

After successful guarded apply:

```bash
git status -sb
git diff --cached --check
git diff --cached --stat
git diff --cached --name-status
git diff --cached -- README.md docs/INDEX.md docs/governance/DECISIONS.md
git diff --cached -- docs/architecture docs/requirements docs/governance docs/verification
```

The patch was constructed against exported documentation snapshots, **not** verified against the actual Ubuntu repo. Git patching is not a multi-file filesystem transaction or a guarantee against concurrent path mutation. Review staged **new documents as well as navigation changes**, link/secret-check them and inspect the diff before committing. **Do not start a PoC/implementation merely because docs exist.**

## 4. Separate adoption and execution gates

- **DOCS:** F01–F03 remediated by removing the untrusted custom installer, preserving the untouched historical baseline and using a checkable Git patch; F04 narrow Jira egress delta **recorded as pending policy confirmation**; F15 dependency ordering corrected. Actual diff/commit still pending.
- **SYNTHETIC PoC:** SDK supervisor vs sandbox/process privilege layout, protected verifier oracle, bounded workload, no real tokens/source; all controls need evidence.
- **REAL DATA:** Jira read egress, ticket fields and access/retention policy adopted; new Viewer visibility remains default-deny.
- **PRIVILEGED REMOTE WRITE:** candidate/revision identity, safe CI enrollment, fixed renderer, two-step delivery ledger, unknown response/revocation handling and credential scope independently verified.

## 5. Unresolved obligations, retained as OPEN

- V-01..V-14, legacy risks and VNX-V01..V15 have no runtime proof; no test results or successful GitLab/API calls are asserted.
- New Jira/agent/patch data classification, retention and Viewer policy are not inherited from M01; fail closed on new data access and do not store real sensitive content durably without an explicit later policy.
- Exact internal service authentication, model selection/VRAM, sandbox enforcement, API contracts, GitLab token scopes, backup/restore and numeric budgets remain open.
- Draft MR confirmation, independent local verification, CI outcome and human merge are distinct events. CI feedback/rework extension is not implicitly included in the initial slice.
- Project Control must assign specific work-package/ticket IDs and reconcile paused M01 work against **live Git**, not old snapshots.

## 6. Adoption evidence (not yet collected)

| Item | Present status |
|---|---|
| Owner vNext policy direction P-01..03 | ACCEPTED 2026-10-22 |
| Go/PostgreSQL and Python/OpenHands design directions | OWNER SELECTED |
| Updated Markdown artifacts | PREPARED IN THIS DELIVERY |
| User's live Git working tree changed | NOT PERFORMED |
| Diff review / Git commit hash | NOT AVAILABLE |
| New implementation / PoC | NOT STARTED |

Record the actual change/commit only after reviewing the local Git diff. Do not infer official ticket status or runtime success from this docs update.
