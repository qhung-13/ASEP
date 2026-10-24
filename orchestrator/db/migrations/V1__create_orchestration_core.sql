-- VN-T10 MVP prototype: Go-owned objects ONLY. This does not touch Java schemas.
-- All identities are supplied by trusted services. No agent identity is authoritative.
CREATE TABLE orchestration.workflow_runs (
    run_id uuid PRIMARY KEY,
    command_id uuid NOT NULL UNIQUE,
    command_payload_sha256 char(64) NOT NULL CHECK (command_payload_sha256 ~ '^[0-9a-f]{64}$'),
    subject_key varchar(256) NOT NULL CHECK (length(trim(subject_key)) > 0),
    actor_id varchar(128) NOT NULL,
    trigger_kind varchar(24) NOT NULL DEFAULT 'MANUAL' CHECK (trigger_kind = 'MANUAL'),
    jira_site_id varchar(160) NOT NULL,
    jira_project_id varchar(160) NOT NULL,
    jira_issue_id varchar(160) NOT NULL,
    gitlab_project_id varchar(160) NOT NULL,
    repository_base_sha varchar(64) NOT NULL CHECK (length(repository_base_sha) IN (40,64)),
    target_branch varchar(128) NOT NULL,
    observed_target_head_sha varchar(64) NOT NULL CHECK (length(observed_target_head_sha) IN (40,64)),
    source_fingerprint_sha256 char(64) NOT NULL CHECK (source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    policy_id varchar(128) NOT NULL,
    policy_epoch bigint NOT NULL CHECK (policy_epoch > 0),
    state varchar(40) NOT NULL DEFAULT 'ADMITTED'
      CHECK (state IN ('ADMITTED','INVESTIGATING','PLANNING','IMPLEMENTING',
        'VERIFYING','REVIEWING','DELIVERY_PENDING','CANCEL_REQUESTED',
        'RECONCILIATION_REQUIRED','COMPLETED_DRAFT_MR','BLOCKED','ESCALATED',
        'FAILED','CANCELLED','SUPERSEDED')),
    run_version bigint NOT NULL DEFAULT 0 CHECK (run_version >= 0),
    budget_limit integer NOT NULL CHECK (budget_limit BETWEEN 1 AND 100),
    budget_used integer NOT NULL DEFAULT 0 CHECK (budget_used >= 0 AND budget_used <= budget_limit),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- The active subject predicate, not command_id alone, enforces at-most-one active run.
CREATE UNIQUE INDEX uq_orch_one_active_subject
    ON orchestration.workflow_runs (subject_key)
    WHERE state NOT IN ('COMPLETED_DRAFT_MR','BLOCKED','ESCALATED',
                        'FAILED','CANCELLED','SUPERSEDED');

CREATE TABLE orchestration.stage_attempts (
    attempt_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_runs(run_id),
    stage varchar(40) NOT NULL,
    semantic_attempt_no integer NOT NULL CHECK (semantic_attempt_no >= 1),
    state varchar(24) NOT NULL DEFAULT 'PENDING'
      CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    input_artifact_ref varchar(256),
    output_artifact_ref varchar(256),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE (run_id, stage, semantic_attempt_no),
    UNIQUE (attempt_id, run_id)
);

CREATE TABLE orchestration.task_deliveries (
    task_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_runs(run_id),
    attempt_id uuid NOT NULL,
    stage varchar(40) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
      CHECK (status IN ('QUEUED','LEASED','COMPLETED','CANCELLED','EXHAUSTED')),
    claimant_id varchar(128),
    lease_epoch bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_expires_at timestamptz,
    available_at timestamptz NOT NULL DEFAULT now(),
    delivery_count integer NOT NULL DEFAULT 0 CHECK (delivery_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (attempt_id, run_id) REFERENCES orchestration.stage_attempts(attempt_id, run_id),
    CHECK (status <> 'LEASED' OR (claimant_id IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_orch_one_outstanding_task_per_run
    ON orchestration.task_deliveries (run_id)
    WHERE status IN ('QUEUED','LEASED');
CREATE INDEX idx_orch_ready_tasks
    ON orchestration.task_deliveries (available_at, created_at)
    WHERE status = 'QUEUED';
CREATE INDEX idx_orch_expired_leases
    ON orchestration.task_deliveries (lease_expires_at)
    WHERE status = 'LEASED';

CREATE TABLE orchestration.workflow_transitions (
    transition_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_runs(run_id),
    attempt_id uuid,
    from_state varchar(40) NOT NULL,
    to_state varchar(40) NOT NULL,
    prior_run_version bigint NOT NULL CHECK (prior_run_version >= 0),
    evidence_ref varchar(256),
    budget_debit integer NOT NULL DEFAULT 0 CHECK (budget_debit >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (attempt_id, run_id) REFERENCES orchestration.stage_attempts(attempt_id, run_id),
    UNIQUE (run_id, prior_run_version)
);

CREATE TABLE orchestration.workflow_outbox (
    message_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_runs(run_id),
    event_type varchar(80) NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
      CHECK (status IN ('PENDING','DELIVERING','DELIVERED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz
);
CREATE INDEX idx_orch_pending_outbox
    ON orchestration.workflow_outbox (created_at)
    WHERE status = 'PENDING';
