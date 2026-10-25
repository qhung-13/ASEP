# ADR-VN-02 — Scoped VN-T11 B2 locked acceptance clarification

**Date:** 2026-10-25
**Status:** Implementation clarification submitted for independent technical review.
**Authority:** Owner's VN-T11 Batch 2 Implementation Contract, provided 2026-10-25,
explicitly selects Locked Acceptance Point for the private synthetic B2 flow.
This records that narrow direction; it does not claim approval of a broader ADR
rewrite, production result API, policy integration, B3 or B4.
**Original:** [ADR-VN-02](ADR-VN-02-DURABLE-ORCHESTRATION.md), unchanged, including
its 2026-10-22 provenance and stricter durable-commit-boundary wording.

For the Go-local `INVESTIGATING → PLANNING` synthetic result transition only:

1. Lock the authoritative run `FOR UPDATE`, then the task `FOR UPDATE` by stable
   identity, without time-dependent predicates during lock acquisition.
2. In a fresh statement after both locks, capture one PostgreSQL
   `clock_timestamp()` value in a materialized CTE. The conditional task update
   validates current task/attempt/stage/claimant/epoch and unexpired authority
   against that value. Run version, stage, policy epoch and source fingerprint
   were validated under the still-held exclusive run lock. Attempt completion
   must also succeed; otherwise the entire transaction rolls back.
3. That timestamp is the locked acceptance/serialization point, recorded in the
   result receipt. Expiry before it rejects, including expiry during lock wait.
   Expiry afterward does not invalidate that accepted decision. All necessary
   locks remain held through commit; no execution or external call uses them.
4. Completion, successor, transition, outbox and receipt commit atomically.
   Heartbeat/claim continue using run → task order. Future cancellation/policy
   writers must serialize on the run; their implementation is outside B2.

Exact operation/hash replay returns historical receipt IDs without granting
execution authority or requiring a historical lease to be live. Mutable expiry
and transport delivery count are not part of the version-1 canonical payload.
Go computes its fixed-order JSON SHA256; this is not a cross-language wire spec.

A failed COMMIT response is not rollback proof. Bounded, independent-connection
receipt lookup may confirm acceptance; absence or unavailable evidence remains
UNKNOWN, with the original operation identity retained for later lookup. No
automatic transition retry occurs. Remote GitLab/Jira effects are not covered.

Finite PostgreSQL fault tests provide evidence for exercised schedules, not a
proof of every schedule, fairness, cancellation behavior or exactly-once remote
effects. The original ADR's broader outstanding obligations remain unchanged.
