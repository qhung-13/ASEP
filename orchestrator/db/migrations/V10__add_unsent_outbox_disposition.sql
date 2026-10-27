-- Sender-only terminal evidence is distinct from a receiver acknowledgement.
-- Existing rows/identities and genuine UNKNOWN delivery attempts are untouched.
ALTER TABLE orchestration.workflow_outbox
    DROP CONSTRAINT workflow_outbox_status_check;
ALTER TABLE orchestration.workflow_outbox ADD CONSTRAINT workflow_outbox_status_check
    CHECK(status IN ('PENDING','DELIVERING','DELIVERED','OBSOLETE'));

CREATE TABLE orchestration.workflow_synthetic_sender_dispositions (
    message_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    payload_sha256 char(64) NOT NULL,
    intent_run_version bigint NOT NULL CHECK(intent_run_version>0),
    observed_prior_run_version bigint NOT NULL,
    disposition varchar(32) NOT NULL CHECK(disposition='NEVER_SENT_OBSOLETE'),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(observed_prior_run_version>=intent_run_version),
    FOREIGN KEY(message_id,run_id,payload_sha256)
      REFERENCES orchestration.workflow_synthetic_delivery_policies(message_id,run_id,payload_sha256),
    FOREIGN KEY(run_id,observed_prior_run_version)
      REFERENCES orchestration.workflow_transitions(run_id,prior_run_version)
);
GRANT SELECT,INSERT ON orchestration.workflow_synthetic_sender_dispositions TO asep_orch_runtime;
