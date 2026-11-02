-- Go-owned delivery authority. Roles are provisioned by a trusted operator, never here.
-- No synthetic profile or Java cross-schema write is an authorization source.
CREATE TABLE orchestration.delivery_policies (
    policy_id varchar(101) PRIMARY KEY,
    policy_epoch bigint NOT NULL CHECK (policy_epoch > 0),
    project_id bigint NOT NULL CHECK (project_id = 87786140),
    repository_path text NOT NULL CHECK (repository_path = 'qhung-13/asep-m01-sandbox'),
    target_branch text NOT NULL CHECK (target_branch = 'main'),
    enabled boolean NOT NULL,
    expires_at timestamptz NOT NULL,
    changed_by text NOT NULL DEFAULT session_user,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION orchestration.guard_delivery_policy() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NOT pg_has_role(session_user,'asep_orch_authority','USAGE') THEN
        RAISE EXCEPTION 'only the protected policy authority may update';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.policy_id IS DISTINCT FROM OLD.policy_id
        OR NEW.project_id IS DISTINCT FROM OLD.project_id OR NEW.repository_path IS DISTINCT FROM OLD.repository_path
        OR NEW.target_branch IS DISTINCT FROM OLD.target_branch OR NEW.policy_epoch < OLD.policy_epoch
        OR (NEW.policy_epoch = OLD.policy_epoch AND (NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR (NOT OLD.enabled AND NEW.enabled)))) THEN
        RAISE EXCEPTION 'delivery policy identity/epoch is immutable';
    END IF;
    NEW.changed_by := session_user; NEW.changed_at := clock_timestamp();
    RETURN NEW;
END; $$;
CREATE TRIGGER delivery_policy_guard BEFORE INSERT OR UPDATE ON orchestration.delivery_policies
FOR EACH ROW EXECUTE FUNCTION orchestration.guard_delivery_policy();
REVOKE ALL ON FUNCTION orchestration.guard_delivery_policy() FROM PUBLIC;

-- A protected review controller attests the exact request after independent verification.
-- Merely submitting a request or possessing the Go worker role cannot issue this evidence.
CREATE TABLE orchestration.delivery_review_evidence (
    evidence_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_runs(run_id),
    producer_attempt_id uuid NOT NULL,
    review_attempt_id uuid NOT NULL,
    task_id uuid NOT NULL REFERENCES orchestration.task_deliveries(task_id),
    claimant_id varchar(128) NOT NULL CHECK (length(claimant_id)>0),
    lease_epoch bigint NOT NULL CHECK (lease_epoch>0),
    expected_run_version bigint NOT NULL CHECK (expected_run_version>0),
    policy_id varchar(101) NOT NULL REFERENCES orchestration.delivery_policies(policy_id),
    policy_epoch bigint NOT NULL CHECK (policy_epoch>0),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    request_json jsonb NOT NULL CHECK (jsonb_typeof(request_json)='object'),
    issued_by text NOT NULL DEFAULT session_user,
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    FOREIGN KEY (producer_attempt_id,run_id) REFERENCES orchestration.stage_attempts(attempt_id,run_id),
    FOREIGN KEY (review_attempt_id,run_id) REFERENCES orchestration.stage_attempts(attempt_id,run_id),
    CHECK (expires_at>issued_at AND expires_at<=issued_at+interval '1 hour')
);
CREATE FUNCTION orchestration.guard_delivery_review() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.issued_by := session_user; NEW.issued_at := clock_timestamp();
    ELSE
        IF NOT pg_has_role(session_user,'asep_orch_authority','USAGE') THEN
            RAISE EXCEPTION 'only the protected review authority may revoke';
        END IF;
        IF (to_jsonb(NEW)-'revoked_at') IS DISTINCT FROM (to_jsonb(OLD)-'revoked_at')
            OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL THEN
            RAISE EXCEPTION 'delivery review evidence is immutable';
        END IF;
        NEW.revoked_at := clock_timestamp();
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER delivery_review_guard BEFORE INSERT OR UPDATE ON orchestration.delivery_review_evidence
FOR EACH ROW EXECUTE FUNCTION orchestration.guard_delivery_review();
REVOKE ALL ON FUNCTION orchestration.guard_delivery_review() FROM PUBLIC;

CREATE TABLE orchestration.delivery_authorizations (
    operation_id uuid PRIMARY KEY,
    run_id uuid NOT NULL UNIQUE REFERENCES orchestration.workflow_runs(run_id),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    evidence_id uuid NOT NULL UNIQUE REFERENCES orchestration.delivery_review_evidence(evidence_id),
    delivery_attempt_id uuid NOT NULL,
    transition_id uuid NOT NULL UNIQUE REFERENCES orchestration.workflow_transitions(transition_id),
    outbox_message_id uuid NOT NULL UNIQUE REFERENCES orchestration.workflow_outbox(message_id),
    accepted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (delivery_attempt_id,run_id) REFERENCES orchestration.stage_attempts(attempt_id,run_id)
);
GRANT SELECT ON orchestration.delivery_policies,orchestration.delivery_review_evidence TO asep_orch_runtime;
-- PostgreSQL FOR SHARE requires UPDATE on at least one column. Grant only immutable
-- keys; triggers reject every actual runtime UPDATE. No evidence/policy field is writable.
GRANT UPDATE(policy_id) ON orchestration.delivery_policies TO asep_orch_runtime;
GRANT UPDATE(evidence_id) ON orchestration.delivery_review_evidence TO asep_orch_runtime;
GRANT SELECT,INSERT ON orchestration.delivery_authorizations TO asep_orch_runtime;
GRANT USAGE ON SCHEMA orchestration TO asep_orch_authority;
GRANT SELECT,INSERT ON orchestration.delivery_policies,orchestration.delivery_review_evidence TO asep_orch_authority;
GRANT UPDATE(policy_epoch,enabled,expires_at) ON orchestration.delivery_policies TO asep_orch_authority;
GRANT UPDATE(revoked_at) ON orchestration.delivery_review_evidence TO asep_orch_authority;

-- Preserve the entire legacy cancellation predicate. Add a separately typed Go
-- delivery stop intent; it has no synthetic worker task or cleanup receipt.
DO $$
DECLARE previous text;
BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO STRICT previous
      FROM pg_constraint WHERE conname='ck_cancel_request_metadata'
      AND conrelid='orchestration.workflow_transitions'::regclass;
    ALTER TABLE orchestration.workflow_transitions DROP CONSTRAINT ck_cancel_request_metadata;
    EXECUTE 'ALTER TABLE orchestration.workflow_transitions ADD CONSTRAINT ck_cancel_request_metadata CHECK (('
      || previous || ') OR (control_kind IS NOT NULL AND control_kind=''DELIVERY_CANCEL''
        AND control_operation_id IS NOT NULL AND control_digest_version IS NOT NULL AND control_digest_version=1
        AND control_payload_sha256 IS NOT NULL AND control_payload_sha256 ~ ''^[0-9a-f]{64}$''
        AND control_task_id IS NULL AND control_claimant_id IS NULL AND control_lease_epoch IS NULL
        AND control_policy_epoch IS NULL AND control_source_fingerprint_sha256 IS NULL
        AND from_state IN (''REVIEWING'',''DELIVERY_PENDING'') AND to_state=''CANCEL_REQUESTED''
        AND prior_run_version>0 AND budget_debit=0))';
END $$;
