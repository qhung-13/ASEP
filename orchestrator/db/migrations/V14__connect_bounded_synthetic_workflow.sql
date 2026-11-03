-- VN-T15: finite synthetic integration profile on the existing workflow/task engine.
-- No production defaults, live enrollment, Owner grant or external-write authority.
CREATE TABLE orchestration.workflow_slice_profiles (
    run_id uuid PRIMARY KEY REFERENCES orchestration.workflow_runs(run_id),
    scope_prefix varchar(80) NOT NULL CHECK (scope_prefix ~ '^synthetic:vn-t10-it-[0-9a-f]{32}$'),
    expires_at timestamptz NOT NULL,
    model_token_limit integer NOT NULL CHECK (model_token_limit BETWEEN 1 AND 4096),
    model_tokens_reserved integer NOT NULL DEFAULT 0 CHECK (model_tokens_reserved >= 0 AND model_tokens_reserved <= model_token_limit),
    maximum_model_calls integer NOT NULL CHECK (maximum_model_calls BETWEEN 1 AND 8),
    model_calls_reserved integer NOT NULL DEFAULT 0 CHECK (model_calls_reserved >= 0 AND model_calls_reserved <= maximum_model_calls),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '5 minutes')
);
-- Reservations bind the actual lease, so transport redelivery consumes another
-- finite call allowance, while replay of the same reservation does not.
CREATE TABLE orchestration.workflow_slice_reservations (
    task_id uuid NOT NULL,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_slice_profiles(run_id),
    attempt_id uuid NOT NULL,
    lease_epoch bigint NOT NULL CHECK (lease_epoch > 0),
    tokens integer NOT NULL CHECK (tokens BETWEEN 1 AND 128),
    PRIMARY KEY(task_id, lease_epoch),
    FOREIGN KEY(task_id,run_id,attempt_id) REFERENCES orchestration.task_deliveries(task_id,run_id,attempt_id)
);
-- Reuse the existing logical result receipt for terminal stage decisions too.
ALTER TABLE orchestration.workflow_result_receipts ALTER COLUMN next_task_id DROP NOT NULL;
ALTER TABLE orchestration.workflow_result_receipts ALTER COLUMN next_attempt_id DROP NOT NULL;
ALTER TABLE orchestration.workflow_result_receipts ADD CONSTRAINT slice_successor_pair
    CHECK ((next_task_id IS NULL) = (next_attempt_id IS NULL));
GRANT SELECT,INSERT ON orchestration.workflow_slice_profiles,orchestration.workflow_slice_reservations TO asep_orch_runtime;
GRANT UPDATE(model_tokens_reserved,model_calls_reserved) ON orchestration.workflow_slice_profiles TO asep_orch_runtime;
-- The existing protected issuer may inspect only binding/freshness, never write
-- authoritative workflow state or read raw agent output. Pending implementation
-- is visible only with its current live producer lease, allowing trusted seal
-- registration BEFORE the implementation handoff (no success prerequisite).
CREATE VIEW orchestration.slice_candidate_context AS
 SELECT r.run_id,r.subject_key,r.state,r.run_version,r.policy_id,r.policy_epoch,p.expires_at,
        r.repository_base_sha,r.source_fingerprint_sha256,a.attempt_id AS producer_attempt_id,
        t.task_id AS producer_task_id,t.claimant_id,t.lease_epoch
 FROM orchestration.workflow_runs r JOIN orchestration.workflow_slice_profiles p ON p.run_id=r.run_id
 JOIN orchestration.stage_attempts a ON a.run_id=r.run_id AND a.stage='IMPLEMENTING' AND a.state IN ('RUNNING','SUCCEEDED')
 JOIN orchestration.task_deliveries t ON t.run_id=r.run_id AND t.attempt_id=a.attempt_id AND t.status IN ('LEASED','COMPLETED')
 WHERE r.subject_key LIKE p.scope_prefix || ':%'
 AND ((r.state='IMPLEMENTING' AND a.state='RUNNING' AND t.status='LEASED' AND t.lease_expires_at>clock_timestamp())
   OR (r.state IN ('VERIFYING','REVIEWING') AND a.state='SUCCEEDED' AND t.status='COMPLETED'))
 AND a.semantic_attempt_no=(SELECT max(latest.semantic_attempt_no) FROM orchestration.stage_attempts latest WHERE latest.run_id=r.run_id AND latest.stage='IMPLEMENTING');
GRANT SELECT ON orchestration.slice_candidate_context TO asep_orch_authority;
