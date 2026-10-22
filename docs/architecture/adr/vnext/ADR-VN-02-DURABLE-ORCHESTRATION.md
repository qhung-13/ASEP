# ADR-VN-02 — Go + PostgreSQL Minimal Durable Orchestration

**Date:** 2026-10-22
**Status:** OWNER-SELECTED vNext DESIGN DECISION (2026-10-22); SQL concurrency/recovery and live repository adoption NOT VERIFIED.
**Related:** ADR-002 (approved invariant baseline), TSE-02 PostgreSQL, TSE-03 PostgreSQL-backed execution.

## Problem

A Jira-to-Draft-MR workflow can run through many stages and failures. Agent completion, worker execution and remote GitLab effects must not be conflated with valid business progress. M01 ADR-002 already requires durable admission, atomic claims, leases/fencing, bounded retry/cancel and reconciliation. vNext needs those guarantees across Go and Python processes without adding unnecessary queue infrastructure.

## Owner-selected decision (design level)

Build a **minimal explicit Go workflow state machine** using domain-owned PostgreSQL tables and a durable SQL task queue; no required River OSS, Temporal, Redis or RabbitMQ for the first vertical slice.

- `WorkflowRun`: business identity, authorization/policy revision, state, source/ticket revision and budget.
- `StageAttempt`: semantic attempt, stage outcome, input/output artifact references, retry/rework decision.
- `TaskDelivery`: queued/leased task, claimant identity, lease expiry, monotonically increasing fencing/ownership epoch.
- `WorkflowTransition`: durable audited decision from a trusted evidence/policy gate.
- `ExternalActionIntent`: durable intent to request a privileged operation, but **not** remote completion authority.

The exact table/enum names are examples and require schema review. Go is the **sole authoritative workflow-transition writer**. Agent result validation and independent verification precede transition commit.

## Scheduling design and transaction boundaries

- Persist task and workflow state in a valid PostgreSQL transaction before execution.
- Atomically claim eligible work via SQL row lock / conditional update and assign a new lease epoch; verify with concurrency tests. Use database time for expiration and compare-and-set expected task state/version/epoch at durable commit.
- Heartbeat and lease expiry are controlled by Go. An expired/stale lease cannot commit a result **even before any new worker reclaims it**. Long-running agent execution must never retain a DB row lock.
- Reclaim after crash; do not equate infrastructure redelivery with a new semantic Implementation attempt.
- Keep semantic correction/replan budgets separate from transport/job retries.
- A workflow transition, its successor task and required outbox entry commit in **one Go-local transaction**. Cross-owner inbox/outbox delivery can repeat, so consumer dedupe must use ID plus expected payload hash and return the original logical result. No distributed transaction across Java/Go/GitLab.
- Cancellation invalidates authority and requires enforceable sandbox termination/cleanup; cooperative cancellation alone is insufficient.

## Gate decisions

`ADVANCE`, `REWORK`, `REPLAN`, `RETRY_INFRA`, `BLOCK`, `ESCALATE` are *candidate* gate outputs. Every gate needs stage-appropriate evidence: Investigation/Planning bind the inspected source/ticket snapshot and bounded plan, while Verification/Delivery bind the **sealed candidate digest**, protected test profile and current policy/remote revisions. A model cannot directly call an authoritative state transition.

## Alternatives

| Alternative | Strength | Tradeoff |
|---|---|---|
| **Custom Go + PostgreSQL queue** | Fewest initial dependencies, precise learning/control and fit with accepted direction | ASEP must test locking, leases, fencing, fairness, recovery and observability thoroughly |
| Go + PostgreSQL + River OSS | Mature scheduling primitives, transactional enqueue | Still need ASEP state machine/ledger; migration of pending jobs and duplicate prevention requires design |
| Go + Temporal OSS | Durable workflow history and timers available | Additional operational/service/versioning complexity and a different authoritative-workflow model |

River/Temporal are **deferred alternatives**, not mandatory future scale steps. Scaling can initially come from bounded worker concurrency, task partitioning and PostgreSQL tuning. Later changing scheduler is not automatically lossless.

## Safety invariants inherited from ADR-002

- At-least-once task delivery/computation must not become duplicate valid transitions or duplicate GitLab effects.
- Monotonic lease ownership/fencing checked at the **durable commit boundary**.
- Workflow state and task scheduler state have separate meanings.
- Outbox dispatch may duplicate, so consumers must be idempotent.
- Unknown remote side effects are reconciled by the Trusted External Action Executor, not blindly retried by Go.
- Revision/policy revocation can block further stage progress and remote actions.
- No assertion of exactly-once external effects or zero TOCTOU race.

## Minimum state/transaction contract (design obligation; not SQL implementation)

The [canonical execution contract](../../vnext/EXECUTION_CONTRACTS_AND_GATES.md) defines admission dedupe vs explicit rerun, nonterminal/terminal transitions, cancellation races, task lease epoch and operation UNKNOWN as a separate outcome facet. A cancellation result must not rewrite an already accepted remote effect as absent. Go must keep semantic correction budgets durable and independent from task redelivery.

## Required verification (OPEN)

Atomic claims under contention, worker crash and reclaims, lost heartbeat, late stale completion, repeated command/handoff, restart durability, cancellation enforcement, task-budget correctness, outbox crash window, source-revision change, ExternalOperation UNKNOWN reconciliation, PostgreSQL outage and backup/recovery.

## Consequences and revisit conditions

Correctness burden is nontrivial; keep engine intentionally small and avoid generic graphical workflow features. Consider queue library/Temporal only if fault-injection results, operational support or throughput evidence show clear advantage. No PoC has been run for this decision at authoring time.
