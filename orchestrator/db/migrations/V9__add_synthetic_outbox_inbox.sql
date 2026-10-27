-- Go-local synthetic receiver only; no shared Java/Go production database.
ALTER TABLE orchestration.workflow_outbox ADD CONSTRAINT uq_synthetic_message_hash
    UNIQUE(message_id,run_id,payload_sha256);
CREATE TABLE orchestration.workflow_synthetic_delivery_policies (
    message_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_synthetic_profiles(run_id),
    payload_sha256 char(64) NOT NULL,
    policy_epoch bigint NOT NULL CHECK(policy_epoch>0),
    source_sha256 char(64) NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
    maximum_attempts integer NOT NULL CHECK(maximum_attempts>0),
    lease_seconds integer NOT NULL CHECK(lease_seconds BETWEEN 1 AND 300),
    FOREIGN KEY(message_id,run_id,payload_sha256)
      REFERENCES orchestration.workflow_outbox(message_id,run_id,payload_sha256),
    UNIQUE(message_id,run_id,payload_sha256)
);
CREATE TABLE orchestration.workflow_synthetic_deliveries (
    message_id uuid PRIMARY KEY REFERENCES orchestration.workflow_synthetic_delivery_policies(message_id),
    phase varchar(16) NOT NULL CHECK(phase IN ('READY','UNKNOWN','DEFERRED','DELIVERED','OBSOLETE','EXHAUSTED')),
    attempts integer NOT NULL CHECK(attempts>=0),
    lease_epoch bigint NOT NULL CHECK(lease_epoch=attempts),
    lease_until timestamptz
);
CREATE TABLE orchestration.workflow_synthetic_delivery_attempts (
    message_id uuid NOT NULL REFERENCES orchestration.workflow_synthetic_delivery_policies(message_id),
    lease_epoch bigint NOT NULL CHECK(lease_epoch>0),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(message_id,lease_epoch)
);
CREATE TABLE orchestration.workflow_synthetic_inbox (
    message_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    payload_sha256 char(64) NOT NULL,
    task_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    run_version bigint NOT NULL CHECK(run_version>0),
    receipt_id uuid NOT NULL UNIQUE,
    disposition varchar(16) NOT NULL CHECK(disposition IN ('ACCEPTED','OBSOLETE')),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(message_id,run_id,payload_sha256)
      REFERENCES orchestration.workflow_synthetic_delivery_policies(message_id,run_id,payload_sha256),
    FOREIGN KEY(task_id,run_id,attempt_id)
      REFERENCES orchestration.task_deliveries(task_id,run_id,attempt_id),
    UNIQUE(message_id,run_id,receipt_id,disposition)
);
CREATE TABLE orchestration.workflow_synthetic_effects (
    message_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    disposition varchar(16) NOT NULL CHECK(disposition='ACCEPTED'),
    FOREIGN KEY(message_id,run_id,receipt_id,disposition)
      REFERENCES orchestration.workflow_synthetic_inbox(message_id,run_id,receipt_id,disposition)
);
CREATE TABLE orchestration.workflow_synthetic_delivery_receipts (
    message_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    disposition varchar(16) NOT NULL,
    FOREIGN KEY(message_id,run_id,receipt_id,disposition)
      REFERENCES orchestration.workflow_synthetic_inbox(message_id,run_id,receipt_id,disposition)
);
GRANT SELECT,INSERT ON orchestration.workflow_synthetic_delivery_policies,
    orchestration.workflow_synthetic_delivery_attempts,orchestration.workflow_synthetic_inbox,
    orchestration.workflow_synthetic_effects,orchestration.workflow_synthetic_delivery_receipts TO asep_orch_runtime;
GRANT SELECT,INSERT,UPDATE ON orchestration.workflow_synthetic_deliveries TO asep_orch_runtime;
