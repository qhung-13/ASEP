-- Explicit controller-produced harmless probe entry; no claim of AI upstream stages.
ALTER TABLE orchestration.workflow_runs DROP CONSTRAINT workflow_runs_trigger_kind_check;
ALTER TABLE orchestration.workflow_runs ADD CONSTRAINT workflow_runs_trigger_kind_check
  CHECK (trigger_kind IN ('MANUAL','CONTROLLER_PROBE'));
CREATE TABLE orchestration.delivery_verification_profiles (
    policy_id varchar(101) NOT NULL REFERENCES orchestration.delivery_policies(policy_id),
    policy_epoch bigint NOT NULL CHECK (policy_epoch>0),
    profile_sha256 char(64) NOT NULL CHECK (profile_sha256 ~ '^[0-9a-f]{64}$'),
    harness_sha256 char(64) NOT NULL CHECK (harness_sha256 ~ '^[0-9a-f]{64}$'),
    image_sha256 char(64) NOT NULL CHECK (image_sha256 ~ '^[0-9a-f]{64}$'),
    minimum_tests integer NOT NULL CHECK (minimum_tests>=2),
    maximum_age_seconds integer NOT NULL CHECK (maximum_age_seconds BETWEEN 1 AND 3600),
    PRIMARY KEY(policy_id,policy_epoch)
);
CREATE TABLE orchestration.controlled_candidate_evidence (
    evidence_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES orchestration.workflow_runs(run_id),
    producer_attempt_id uuid NOT NULL,
    producer_task_id uuid NOT NULL,
    claimant_id varchar(128) NOT NULL,
    lease_epoch bigint NOT NULL CHECK (lease_epoch>0),
    submission_hash char(64) NOT NULL CHECK (submission_hash ~ '^[0-9a-f]{64}$'),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    request_json jsonb NOT NULL CHECK (jsonb_typeof(request_json)='object'),
    seal_json jsonb NOT NULL CHECK (jsonb_typeof(seal_json)='object'),
    verification_json jsonb NOT NULL CHECK (jsonb_typeof(verification_json)='object'),
    issued_by text NOT NULL DEFAULT session_user,
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    FOREIGN KEY(producer_attempt_id,run_id) REFERENCES orchestration.stage_attempts(attempt_id,run_id),
    FOREIGN KEY(producer_task_id,run_id,producer_attempt_id) REFERENCES orchestration.task_deliveries(task_id,run_id,attempt_id),
    UNIQUE(run_id), CHECK (expires_at>issued_at AND expires_at<=issued_at+interval '1 hour')
);
CREATE FUNCTION orchestration.guard_controlled_candidate_evidence() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF TG_OP='INSERT' THEN NEW.issued_by:=session_user; NEW.issued_at:=clock_timestamp();
    ELSE
      IF NOT pg_has_role(session_user,'asep_orch_authority','USAGE')
        OR (to_jsonb(NEW)-'revoked_at') IS DISTINCT FROM (to_jsonb(OLD)-'revoked_at')
        OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'controlled evidence is immutable';
      END IF;
      NEW.revoked_at:=clock_timestamp();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER controlled_evidence_guard BEFORE INSERT OR UPDATE ON orchestration.controlled_candidate_evidence
FOR EACH ROW EXECUTE FUNCTION orchestration.guard_controlled_candidate_evidence();
REVOKE ALL ON FUNCTION orchestration.guard_controlled_candidate_evidence() FROM PUBLIC;
CREATE TABLE orchestration.controlled_review_receipts (
    evidence_id uuid PRIMARY KEY REFERENCES orchestration.controlled_candidate_evidence(evidence_id),
    run_id uuid NOT NULL UNIQUE REFERENCES orchestration.workflow_runs(run_id),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    review_attempt_id uuid NOT NULL,
    review_task_id uuid NOT NULL,
    review_lease_epoch bigint NOT NULL CHECK (review_lease_epoch>0),
    FOREIGN KEY(review_task_id,run_id,review_attempt_id) REFERENCES orchestration.task_deliveries(task_id,run_id,attempt_id)
);
GRANT SELECT ON orchestration.delivery_verification_profiles,orchestration.controlled_candidate_evidence TO asep_orch_runtime;
GRANT UPDATE(evidence_id) ON orchestration.controlled_candidate_evidence TO asep_orch_runtime;
GRANT SELECT,INSERT ON orchestration.controlled_review_receipts TO asep_orch_runtime;
GRANT SELECT,INSERT ON orchestration.delivery_verification_profiles,orchestration.controlled_candidate_evidence TO asep_orch_authority;
GRANT UPDATE(revoked_at) ON orchestration.controlled_candidate_evidence TO asep_orch_authority;
-- Narrow protected issuer view. No workflow write privilege or unrestricted table read.
CREATE VIEW orchestration.controlled_review_context AS
SELECT c.evidence_id,c.run_id,c.producer_attempt_id,c.request_hash,c.request_json,c.expires_at,c.revoked_at,
       m.review_attempt_id,m.review_task_id,m.review_lease_epoch,
       r.state,r.run_version,r.policy_id,r.policy_epoch,t.claimant_id,t.lease_expires_at
FROM orchestration.controlled_candidate_evidence c
JOIN orchestration.controlled_review_receipts m ON m.evidence_id=c.evidence_id AND m.run_id=c.run_id AND m.request_hash=c.request_hash
JOIN orchestration.workflow_runs r ON r.run_id=c.run_id
JOIN orchestration.task_deliveries t ON t.task_id=m.review_task_id AND t.run_id=c.run_id AND t.attempt_id=m.review_attempt_id;
GRANT SELECT ON orchestration.controlled_review_context TO asep_orch_authority;
