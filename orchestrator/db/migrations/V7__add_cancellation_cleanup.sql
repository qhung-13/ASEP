-- RF-07B: private trusted synthetic ownership and cleanup. No legacy adoption.
ALTER TABLE orchestration.workflow_transitions
    ADD CONSTRAINT uq_execution_request_kind UNIQUE (transition_id, run_id, control_kind);

CREATE TABLE orchestration.workflow_execution_inventories (
    run_id uuid PRIMARY KEY REFERENCES orchestration.workflow_runs(run_id),
    keeper_id uuid NOT NULL,
    boot_id uuid NOT NULL,
    keeper_pid integer NOT NULL CHECK (keeper_pid > 0),
    keeper_start bigint NOT NULL CHECK (keeper_start > 0),
    synthetic_prefix varchar(64) NOT NULL CHECK (synthetic_prefix ~ '^synthetic:vn-t10-it-[0-9a-f]{32}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (run_id, keeper_id)
);

CREATE TABLE orchestration.workflow_execution_scopes (
    execution_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    keeper_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id, keeper_id) REFERENCES orchestration.workflow_execution_inventories(run_id, keeper_id),
    UNIQUE (execution_id, run_id, keeper_id)
);

CREATE TABLE orchestration.workflow_execution_bindings (
    execution_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    keeper_id uuid NOT NULL,
    task_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    stage varchar(40) NOT NULL CHECK (stage IN ('INVESTIGATING','PLANNING')),
    claimant_id varchar(128) NOT NULL CHECK (length(trim(claimant_id)) > 0),
    lease_epoch bigint NOT NULL CHECK (lease_epoch > 0),
    run_version bigint NOT NULL CHECK (run_version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (execution_id, run_id, keeper_id)
      REFERENCES orchestration.workflow_execution_scopes(execution_id, run_id, keeper_id),
    FOREIGN KEY (task_id, run_id, attempt_id)
      REFERENCES orchestration.task_deliveries(task_id, run_id, attempt_id)
);

CREATE TABLE orchestration.workflow_cancellation_inventories (
    request_transition_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    keeper_id uuid NOT NULL,
    request_kind varchar(24) NOT NULL DEFAULT 'CANCEL_REQUEST' CHECK (request_kind='CANCEL_REQUEST'),
    manifest_sha256 char(64) NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    scope_count integer NOT NULL CHECK (scope_count >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (request_transition_id, run_id, request_kind)
      REFERENCES orchestration.workflow_transitions(transition_id, run_id, control_kind),
    FOREIGN KEY (run_id, keeper_id) REFERENCES orchestration.workflow_execution_inventories(run_id, keeper_id),
    UNIQUE (request_transition_id, run_id, keeper_id),
    UNIQUE (request_transition_id, run_id, manifest_sha256)
);

CREATE TABLE orchestration.workflow_execution_cleanups (
    observation_id uuid PRIMARY KEY,
    request_transition_id uuid NOT NULL,
    execution_id uuid NOT NULL,
    run_id uuid NOT NULL,
    keeper_id uuid NOT NULL,
    evidence_sha256 char(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('TERM','KILL','EXITED','NOT_STARTED')),
    wait_status integer NOT NULL CHECK (wait_status >= 0),
    scope_closed boolean NOT NULL CHECK (scope_closed),
    reaped boolean NOT NULL,
    workspace_clean boolean NOT NULL CHECK (workspace_clean),
    CHECK ((outcome='NOT_STARTED' AND NOT reaped AND wait_status=0)
        OR (outcome<>'NOT_STARTED' AND reaped)),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (request_transition_id, run_id, keeper_id)
      REFERENCES orchestration.workflow_cancellation_inventories(request_transition_id, run_id, keeper_id),
    FOREIGN KEY (execution_id, run_id, keeper_id)
      REFERENCES orchestration.workflow_execution_scopes(execution_id, run_id, keeper_id),
    UNIQUE (request_transition_id, execution_id)
);

ALTER TABLE orchestration.workflow_transitions
    ADD COLUMN control_request_transition_id uuid,
    ADD COLUMN control_inventory_sha256 char(64),
    ADD COLUMN control_cleanup_sha256 char(64),
    ADD CONSTRAINT fk_cancel_finalize_request
      FOREIGN KEY (control_request_transition_id, run_id, control_inventory_sha256)
      REFERENCES orchestration.workflow_cancellation_inventories(request_transition_id, run_id, manifest_sha256),
    DROP CONSTRAINT ck_cancel_request_metadata,
    ADD CONSTRAINT ck_cancel_request_metadata CHECK (
      (to_state NOT IN ('CANCEL_REQUESTED','CANCELLED')
        AND control_operation_id IS NULL AND control_kind IS NULL
        AND control_digest_version IS NULL AND control_payload_sha256 IS NULL
        AND control_task_id IS NULL AND control_claimant_id IS NULL
        AND control_lease_epoch IS NULL AND control_policy_epoch IS NULL
        AND control_source_fingerprint_sha256 IS NULL
        AND control_request_transition_id IS NULL AND control_inventory_sha256 IS NULL
        AND control_cleanup_sha256 IS NULL)
      OR
      (control_operation_id IS NOT NULL AND control_kind IS NOT NULL
        AND control_digest_version IS NOT NULL AND control_digest_version=1
        AND control_payload_sha256 IS NOT NULL AND control_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND control_task_id IS NOT NULL AND attempt_id IS NOT NULL
        AND control_claimant_id IS NOT NULL AND control_lease_epoch IS NOT NULL
        AND control_lease_epoch >= 0
        AND ((control_lease_epoch=0 AND control_claimant_id='')
          OR (control_lease_epoch>0 AND length(trim(control_claimant_id))>0))
        AND control_policy_epoch IS NOT NULL AND control_policy_epoch>0
        AND control_source_fingerprint_sha256 IS NOT NULL
        AND control_source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
        AND prior_run_version>0 AND budget_debit=0
        AND (
          (control_kind='CANCEL_REQUEST' AND from_state IN ('INVESTIGATING','PLANNING')
            AND to_state='CANCEL_REQUESTED' AND control_request_transition_id IS NULL
            AND control_inventory_sha256 IS NULL AND control_cleanup_sha256 IS NULL)
          OR
          (control_kind='CANCEL_FINALIZE' AND from_state='CANCEL_REQUESTED' AND to_state='CANCELLED'
            AND control_request_transition_id IS NOT NULL
            AND control_inventory_sha256 IS NOT NULL AND control_inventory_sha256 ~ '^[0-9a-f]{64}$'
            AND control_cleanup_sha256 IS NOT NULL AND control_cleanup_sha256 ~ '^[0-9a-f]{64}$')
        ))
    );

-- Private trusted runtime append-only facts, never worker DB/OS capabilities.
GRANT SELECT, INSERT ON orchestration.workflow_execution_inventories,
    orchestration.workflow_execution_scopes, orchestration.workflow_execution_bindings,
    orchestration.workflow_cancellation_inventories, orchestration.workflow_execution_cleanups
    TO asep_orch_runtime;
