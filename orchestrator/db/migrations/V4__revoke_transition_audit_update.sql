-- VN10-CR-04: make the Go-owned workflow transition audit append-only
-- at the restricted runtime role level. Existing V1-V3 remain immutable.
-- Trusted migration owner can still manage schema for approved migrations.
-- The runtime still has SELECT and INSERT, but must never rewrite history.
REVOKE UPDATE ON TABLE orchestration.workflow_transitions FROM asep_orch_runtime;
