-- RF-07A only: typed cancellation request/stop intent on the append-only audit.
-- Legacy V1-V5 rows keep NULL control metadata. No task/epoch/receipt is changed.
ALTER TABLE orchestration.workflow_transitions
    ADD COLUMN control_operation_id uuid,
    ADD COLUMN control_kind varchar(24),
    ADD COLUMN control_digest_version smallint,
    ADD COLUMN control_payload_sha256 char(64),
    ADD COLUMN control_task_id uuid,
    ADD COLUMN control_claimant_id varchar(128),
    ADD COLUMN control_lease_epoch bigint,
    ADD COLUMN control_policy_epoch bigint,
    ADD COLUMN control_source_fingerprint_sha256 char(64),
    ADD CONSTRAINT fk_cancel_request_task
      FOREIGN KEY (control_task_id, run_id, attempt_id)
      REFERENCES orchestration.task_deliveries(task_id, run_id, attempt_id),
    ADD CONSTRAINT ck_cancel_request_metadata CHECK (
      (to_state <> 'CANCEL_REQUESTED' AND control_operation_id IS NULL AND control_kind IS NULL
        AND control_digest_version IS NULL AND control_payload_sha256 IS NULL
        AND control_task_id IS NULL AND control_claimant_id IS NULL
        AND control_lease_epoch IS NULL AND control_policy_epoch IS NULL
        AND control_source_fingerprint_sha256 IS NULL)
      OR
      (control_operation_id IS NOT NULL AND control_kind IS NOT NULL
        AND control_kind = 'CANCEL_REQUEST'
        AND control_digest_version IS NOT NULL AND control_digest_version = 1
        AND control_payload_sha256 IS NOT NULL AND control_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND control_task_id IS NOT NULL AND attempt_id IS NOT NULL
        AND control_claimant_id IS NOT NULL AND control_lease_epoch IS NOT NULL
        AND control_lease_epoch >= 0
        AND ((control_lease_epoch = 0 AND control_claimant_id = '')
          OR (control_lease_epoch > 0 AND length(trim(control_claimant_id)) > 0))
        AND control_policy_epoch IS NOT NULL AND control_policy_epoch > 0
        AND control_source_fingerprint_sha256 IS NOT NULL
        AND control_source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
        AND from_state IN ('INVESTIGATING','PLANNING')
        AND to_state = 'CANCEL_REQUESTED' AND prior_run_version > 0
        AND budget_debit = 0)
    );

-- Separate typed control namespace; this is not a V5 result operation/receipt.
CREATE UNIQUE INDEX uq_cancel_request_operation
    ON orchestration.workflow_transitions(control_operation_id)
    WHERE control_operation_id IS NOT NULL;
-- Existing SELECT/INSERT grants cover new columns. V4 still forbids audit UPDATE;
-- runtime receives no DELETE, DDL, cleanup or migration-owner capabilities.
