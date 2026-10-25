-- VN-T11 B2: immutable logical-result identity, in the same transaction as
-- completion, successor, transition and outbox. V1-V4 remain unchanged.
-- Composite keys prevent a receipt from binding IDs from unrelated runs/tasks.
ALTER TABLE orchestration.task_deliveries
    ADD CONSTRAINT uq_result_task_binding UNIQUE (task_id, run_id, attempt_id);
ALTER TABLE orchestration.workflow_transitions
    ADD CONSTRAINT uq_result_transition_binding
      UNIQUE (transition_id, run_id, attempt_id, prior_run_version);
ALTER TABLE orchestration.workflow_outbox
    ADD CONSTRAINT uq_result_outbox_binding UNIQUE (message_id, run_id);

CREATE TABLE orchestration.workflow_result_receipts (
    operation_id uuid PRIMARY KEY,
    digest_version smallint NOT NULL CHECK (digest_version = 1),
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    run_id uuid NOT NULL REFERENCES orchestration.workflow_runs(run_id),
    task_id uuid NOT NULL UNIQUE,
    attempt_id uuid NOT NULL,
    claimant_id varchar(128) NOT NULL CHECK (length(trim(claimant_id)) > 0),
    lease_epoch bigint NOT NULL CHECK (lease_epoch > 0),
    expected_run_version bigint NOT NULL CHECK (expected_run_version > 0),
    policy_epoch bigint NOT NULL CHECK (policy_epoch > 0),
    source_fingerprint_sha256 char(64) NOT NULL
      CHECK (source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    transition_id uuid NOT NULL UNIQUE,
    next_task_id uuid NOT NULL UNIQUE,
    next_attempt_id uuid NOT NULL UNIQUE,
    outbox_message_id uuid NOT NULL UNIQUE,
    accepted_run_version bigint NOT NULL
      CHECK (accepted_run_version = expected_run_version + 1),
    accepted_at timestamptz NOT NULL,
    FOREIGN KEY (task_id, run_id, attempt_id)
      REFERENCES orchestration.task_deliveries(task_id, run_id, attempt_id),
    FOREIGN KEY (next_task_id, run_id, next_attempt_id)
      REFERENCES orchestration.task_deliveries(task_id, run_id, attempt_id),
    FOREIGN KEY (transition_id, run_id, attempt_id, expected_run_version)
      REFERENCES orchestration.workflow_transitions(transition_id, run_id, attempt_id, prior_run_version),
    FOREIGN KEY (outbox_message_id, run_id)
      REFERENCES orchestration.workflow_outbox(message_id, run_id)
);

-- Append-only for the restricted runtime, not a tamper-proof administrator ledger.
GRANT SELECT, INSERT ON orchestration.workflow_result_receipts TO asep_orch_runtime;
