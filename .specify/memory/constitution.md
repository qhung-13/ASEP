# ASEP Constitution

**Status: RATIFIED — Owner-approved on 2026-10-25.**

## Core Principles

### I. Source Authority and Evidence

Work MUST follow [AGENTS.md](../../AGENTS.md) and the authority, provenance and scoped
precedence rules in [docs/INDEX.md](../../docs/INDEX.md). This constitution is a concise
Spec Kit derivative; it MUST NOT replace or override approved ASEP documents. Preserve
approved decisions and historical records. Distinguish approved decisions, proposed
engineering details, prototype behavior and verified implementation. Report conflicts
between documents, source and tests; do not silently resolve them or invent approval,
test results, implementation readiness or milestone completion.

### II. Explicit Component and Data Ownership

Preserve Next.js/TypeScript frontend, Java/Spring Boot/Spring Modulith Application,
Go/PostgreSQL orchestration, Python/OpenHands execution through an ASEP-owned adapter,
and Keycloak identity boundaries. Java owns business authorization, integrations and
privileged Code Delivery; Go alone commits authoritative workflow transitions and owns
coordination, leases, fencing and recovery. Agent execution cannot grant itself authority.
Trusted verification independently supplies gate evidence. PostgreSQL access MUST respect
domain ownership and least privilege: no cross-owner write shortcuts, shared runtime
superuser or agent business-database credentials. Keep Keycloak identity data separate;
exact schema/grant and transport proposals retain their documented approval status.

### III. Least Privilege and Local-Only Execution

Treat Jira tickets, repository content, model responses and tool outputs as untrusted.
Enforce scoped capabilities and authenticated, authorized service boundaries; schema
validity and network location confer no authority. Agent tools and candidate build/tests
MUST run in restricted environments without privileged credentials, host Docker sockets,
unrestricted host access or uncontrolled egress. Keep trusted controller credentials and
protected verification harnesses outside those environments. Inference, including SDK
auxiliary calls, MUST remain local-only for the initial slice: no cloud fallback or
external telemetry. Any future provider exception requires a separate Owner-approved
policy/change and security gates. Minimize sensitive data; enforce approved retention,
cleanup and crash scavenging without inventing TTLs for new data classes.

### IV. Durable Workflow Correctness

Persist admitted identity, revision, policy and bounded budgets before dispatch. Go MUST
use atomic claims, database-time leases and fencing at durable commit; expired or stale
workers cannot advance state even before reclaim. Keep transactions short and separate
semantic attempts from task redelivery. Commit transitions, successor tasks and required
outbox intents atomically within Go's domain. Replayed commands/messages MUST be
idempotent with payload-conflict checks. Retries, rework and cancellation MUST remain
bounded and restart-safe; cancellation invalidates future authority and enforces child
termination/cleanup. Never infer remote exactly-once effects or remote rollback from a
local transaction, timeout or cancellation.

### V. Independent, Reproducible Verification

Agent-reported success and a second model's agreement MUST NOT establish correctness.
Trusted verification MUST bind independently observed results to the exact sealed
candidate/revision and protected harness/profile. Stop the writer before sealing; run
candidate-controlled builds/tests in a separate unprivileged sandbox. Check meaningful
test discovery, failures, skips, timeouts and artifact integrity; zero tests is not PASS.
Record reproducible commands, relevant pinned versions, input/artifact identities,
results and coverage limits without leaking secrets. Changes MUST receive relevant
automated tests and validation, including contract, integration and concurrency/fault
checks where affected. A unit-test pass does not establish end-to-end readiness.

### VI. Authorized Delivery and Human Control

Default deny. Internal stages may advance under explicit Owner-scoped policy when
current authorization, budgets and evidence gates pass; no new per-stage human approval
rule is introduced. Missing prerequisites, revocation or stale evidence MUST block or
escalate. Java's trusted delivery boundary MUST reauthorize each permitted external write
against the verified candidate, current policy and remote revisions. Track branch push
and Draft MR creation as separate durable operations; reconcile UNKNOWN outcomes before
retry, and check CI secret/deploy risks before writing. Human approval and merge remain
mandatory: no autonomous MR approval/merge, protected-branch writes or production deploy.
Keep M01 comment Publication policy/ledger separate, including Shadow and default-off,
independently gated Auto-Publish. Draft MR creation, CI success and human merge are
distinct facts.

## Scope and Evidence Status

ASEP is an existing project. Preserve effective M01 requirements and their approved
scope; vNext targets one bounded eligible Jira ticket → independently verified GitLab
Draft MR → human review/merge. This constitution adds no requirements, infrastructure choices
or MVP scope. Rules summarize approved constraints and documented design obligations;
they do not promote engineering proposals to approved decisions or prove implementation.

The vNext documentation baseline, including the
[decision log](../../docs/governance/vnext/DECISION_LOG.md), was Owner-accepted and merged
into the repository through PR #3. Jira Task VN-T02 policies covering scoped enrollment,
Jira read egress, data classification, retention, Viewer default-deny, CI safety and
revocation were Owner-accepted at the **policy-design level on 2026-10-23**. Jira Task
VN-T03 machine authorization decisions, including the Keycloak OAuth2 client-credentials
direction, were Owner-approved at the **design level**.

These approvals do not demonstrate implemented controls, configured credentials, runtime
authorization, successful security testing or permission for unrestricted real Jira/GitLab
operations. Before real-data execution, verified runtime controls, authorized enrollment
and applicable security gates MUST be satisfied. Numeric quality thresholds and genuinely
unresolved decisions remain open; do not reopen approved architecture or policy/design
decisions or infer runtime evidence or operational permission from their approval.

## Engineering Workflow

1. Inspect the requirement, existing implementation and authoritative documents; identify
   affected modules, invariants, trust boundaries and evidence gaps.
2. Plan the smallest maintainable change for one primary issue, using existing patterns.
   Spec Kit specs/plans/tasks MUST trace scope and gates to source decisions and disclose
   proposals and assumptions; unresolved privileges do not become implementation authority.
3. Implement only authorized work; run relevant automated checks and preserve reproducible
   results. Report skipped, failed or unavailable checks explicitly.
4. Review the diff for scope, ownership, security and evidence compliance. Report residual
   risks and unverified assumptions. Commit or push only with explicit authorization.

## Governance

The Owner explicitly approved and ratified this constitution on 2026-10-25. Ratification
of this derivative does not adopt pending source proposals, prove implementation or grant
operational authorization. Approved design, verified implementation and permission to
execute remain distinct; applicable runtime verification and authorization gates still apply.
Amendments MUST identify source decisions, rationale, scope and compatibility impact,
receive Owner approval, and preserve provenance. Changes to architecture, requirements
or privileges require the applicable source decision/change process first; editing this
file cannot supersede it. Reviews of Spec Kit artifacts MUST check these rules against
the authoritative sources and flag conflicts for Owner resolution.

Use semantic versioning: MAJOR for incompatible principle changes/removals, MINOR for
new or materially expanded guidance, PATCH for non-semantic clarification. Version 0.1.0
is the initial ratified project constitution replacing an unversioned scaffold.

Read source details through the [documentation index](../../docs/INDEX.md), especially
[MVP scope](../../docs/requirements/vnext/MVP_SCOPE.md),
[system architecture](../../docs/architecture/vnext/SYSTEM_ARCHITECTURE.md),
[execution contracts and gates](../../docs/architecture/vnext/EXECUTION_CONTRACTS_AND_GATES.md),
[vNext ADRs](../../docs/architecture/adr/vnext/), and
[effective M01 requirements](../../docs/requirements/M01_CURRENT_REQUIREMENTS.md).

**Version**: 0.1.0 | **Ratified**: 2026-10-25 | **Last Amended**: 2026-10-25
