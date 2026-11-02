-- Transport acknowledgement never means a remote Draft MR exists.
CREATE TABLE orchestration.delivery_dispatches (
    message_id uuid PRIMARY KEY REFERENCES orchestration.workflow_outbox(message_id),
    claim_token uuid NOT NULL,
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 5),
    lease_expires_at timestamptz NOT NULL,
    acknowledged_at timestamptz
);
CREATE TABLE orchestration.delivery_outcome_inbox (
    callback_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES orchestration.delivery_authorizations(operation_id),
    revision bigint NOT NULL CHECK (revision>0),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    payload jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(operation_id,revision)
);
CREATE TABLE orchestration.delivery_observations (
    operation_id uuid PRIMARY KEY REFERENCES orchestration.delivery_authorizations(operation_id),
    revision bigint NOT NULL CHECK (revision>0),
    push_state varchar(40) NOT NULL,
    mr_state varchar(40) NOT NULL,
    known_mr_iids jsonb NOT NULL CHECK (jsonb_typeof(known_mr_iids)='array'),
    last_confirmed_push_head char(40),
    residual_race boolean NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
GRANT SELECT,INSERT,UPDATE ON orchestration.delivery_dispatches,orchestration.delivery_observations TO asep_orch_runtime;
GRANT SELECT,INSERT ON orchestration.delivery_outcome_inbox TO asep_orch_runtime;
-- Preserve legacy and review/pending cancellation. Only the controlled entry
-- method can cancel IMPLEMENTING; no general agent route is added.
DO $$
DECLARE previous text;
BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO STRICT previous FROM pg_constraint
      WHERE conname='ck_cancel_request_metadata' AND conrelid='orchestration.workflow_transitions'::regclass;
    ALTER TABLE orchestration.workflow_transitions DROP CONSTRAINT ck_cancel_request_metadata;
    EXECUTE 'ALTER TABLE orchestration.workflow_transitions ADD CONSTRAINT ck_cancel_request_metadata CHECK (('
      ||previous||') OR (control_kind IS NOT NULL AND control_kind=''DELIVERY_CANCEL''
      AND control_operation_id IS NOT NULL AND control_digest_version IS NOT NULL AND control_digest_version=1
      AND control_payload_sha256 IS NOT NULL AND control_payload_sha256 ~ ''^[0-9a-f]{64}$''
      AND control_task_id IS NULL AND control_claimant_id IS NULL AND control_lease_epoch IS NULL
      AND control_policy_epoch IS NULL AND control_source_fingerprint_sha256 IS NULL
      AND from_state=''IMPLEMENTING'' AND to_state=''CANCEL_REQUESTED'' AND prior_run_version>0 AND budget_debit=0))';
END $$;

-- Revoking controller evidence also invalidates its derived review. Keep exact
-- history and prohibit runtime mutation; this trigger has one narrowly scoped update.
CREATE FUNCTION orchestration.revoke_derived_delivery_review() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    UPDATE orchestration.delivery_review_evidence SET revoked_at=clock_timestamp()
      WHERE evidence_id=NEW.evidence_id AND revoked_at IS NULL;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION orchestration.revoke_derived_delivery_review() FROM PUBLIC;
CREATE TRIGGER controlled_evidence_revoke_review AFTER UPDATE ON orchestration.controlled_candidate_evidence
FOR EACH ROW WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL)
EXECUTE FUNCTION orchestration.revoke_derived_delivery_review();
