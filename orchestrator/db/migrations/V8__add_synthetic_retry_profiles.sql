-- Explicit trusted synthetic profiles only. No legacy backfill or production defaults.
CREATE TABLE orchestration.workflow_synthetic_profiles (
    run_id uuid PRIMARY KEY REFERENCES orchestration.workflow_runs(run_id),
    delivery_limit integer NOT NULL CHECK (delivery_limit > 0),
    rework_limit integer NOT NULL CHECK (rework_limit >= 0),
    replan_limit integer NOT NULL CHECK (replan_limit >= 0)
);
CREATE TABLE orchestration.workflow_synthetic_policies (
    run_id uuid PRIMARY KEY REFERENCES orchestration.workflow_synthetic_profiles(run_id),
    policy_epoch bigint NOT NULL CHECK (policy_epoch > 0),
    enabled boolean NOT NULL
);
CREATE TABLE orchestration.workflow_retry_receipts (
    operation_id uuid PRIMARY KEY,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    kind varchar(16) NOT NULL CHECK (kind IN ('REWORK','REPLAN','EXHAUST')),
    run_id uuid NOT NULL REFERENCES orchestration.workflow_synthetic_profiles(run_id),
    task_id uuid NOT NULL UNIQUE,
    attempt_id uuid NOT NULL,
    expected_run_version bigint NOT NULL CHECK (expected_run_version > 0),
    transition_id uuid NOT NULL UNIQUE,
    next_task_id uuid,
    next_attempt_id uuid,
    outbox_message_id uuid UNIQUE,
    accepted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((kind='EXHAUST' AND next_task_id IS NULL AND next_attempt_id IS NULL AND outbox_message_id IS NULL)
        OR (kind<>'EXHAUST' AND next_task_id IS NOT NULL AND next_attempt_id IS NOT NULL AND outbox_message_id IS NOT NULL)),
    FOREIGN KEY (task_id,run_id,attempt_id) REFERENCES orchestration.task_deliveries(task_id,run_id,attempt_id),
    FOREIGN KEY (next_task_id,run_id,next_attempt_id) REFERENCES orchestration.task_deliveries(task_id,run_id,attempt_id),
    FOREIGN KEY (transition_id,run_id,attempt_id,expected_run_version)
      REFERENCES orchestration.workflow_transitions(transition_id,run_id,attempt_id,prior_run_version),
    FOREIGN KEY (outbox_message_id,run_id) REFERENCES orchestration.workflow_outbox(message_id,run_id)
);
-- A protected synthetic controller records its bounded decision independently
-- of the worker result. This is not a production verifier/authorization API.
CREATE TABLE orchestration.workflow_synthetic_gate_evidence (
    evidence_ref varchar(256) PRIMARY KEY CHECK (evidence_ref LIKE 'synthetic:vn-t10-it-%'),
    run_id uuid NOT NULL REFERENCES orchestration.workflow_synthetic_profiles(run_id),
    task_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY(task_id,run_id,attempt_id) REFERENCES orchestration.task_deliveries(task_id,run_id,attempt_id)
);
GRANT SELECT,INSERT ON orchestration.workflow_synthetic_profiles,
    orchestration.workflow_retry_receipts,orchestration.workflow_synthetic_gate_evidence TO asep_orch_runtime;
GRANT SELECT,INSERT,UPDATE ON orchestration.workflow_synthetic_policies TO asep_orch_runtime;
