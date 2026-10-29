-- VN-T14: separate, Java-owned LOCAL-ONLY delivery ledger for the enrolled GitLab test project.
-- V1-V3 synthetic VN-T13 tables and their migration checksums are intentionally unchanged.
-- These tables persist send claims and observations; their presence grants NO live-write authority.
CREATE TABLE code_delivery.vn_t14_operations (
    operation_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    project_id bigint NOT NULL CHECK (project_id = 87786140),
    source_branch varchar(80) NOT NULL,
    target_branch varchar(16) NOT NULL CHECK (target_branch = 'main'),
    candidate_commit_sha char(40) NOT NULL CHECK (candidate_commit_sha ~ '^[0-9a-f]{40}$'),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    request_json text NOT NULL CHECK (octet_length(request_json) BETWEEN 1 AND 16384),
    push_state varchar(40) NOT NULL DEFAULT 'PLANNED'
        CHECK (push_state IN ('PLANNED','IN_PROGRESS','CONFIRMED','UNKNOWN','BLOCKED','MANUAL_RECONCILIATION_REQUIRED')),
    mr_state varchar(40) NOT NULL DEFAULT 'PLANNED'
        CHECK (mr_state IN ('PLANNED','IN_PROGRESS','CONFIRMED','UNKNOWN','BLOCKED','MANUAL_RECONCILIATION_REQUIRED')),
    push_claimed boolean NOT NULL DEFAULT false,
    mr_claimed boolean NOT NULL DEFAULT false,
    last_confirmed_push_head char(40) CHECK (last_confirmed_push_head ~ '^[0-9a-f]{40}$'),
    residual_race boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT vn_t14_source_operation_check CHECK (source_branch = 'test/vn-t14/' || operation_id::text),
    CONSTRAINT vn_t14_single_run_project UNIQUE (run_id, project_id),
    CONSTRAINT vn_t14_confirmed_head_check CHECK (
        last_confirmed_push_head IS NULL OR
        (push_claimed AND last_confirmed_push_head = candidate_commit_sha)
    ),
    CONSTRAINT vn_t14_push_claim_check CHECK (
        (push_state NOT IN ('IN_PROGRESS','CONFIRMED','UNKNOWN') OR push_claimed)
        AND (NOT push_claimed OR push_state <> 'PLANNED')
        AND (push_state <> 'CONFIRMED' OR last_confirmed_push_head IS NOT NULL)
    ),
    CONSTRAINT vn_t14_mr_claim_check CHECK (
        (NOT mr_claimed OR (push_claimed AND last_confirmed_push_head IS NOT NULL))
        AND (mr_state NOT IN ('IN_PROGRESS','CONFIRMED','UNKNOWN') OR mr_claimed)
        AND (NOT mr_claimed OR mr_state <> 'PLANNED')
    )
);

-- A remote MR IID is a historical observation, not proof of a currently valid Draft MR.
-- Append-only for the runtime role: later drift/read failures never erase an observed IID.
CREATE TABLE code_delivery.vn_t14_observed_mrs (
    operation_id uuid NOT NULL REFERENCES code_delivery.vn_t14_operations(operation_id),
    mr_iid bigint NOT NULL CHECK (mr_iid > 0),
    first_observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (operation_id, mr_iid)
);

-- Outgoing durable Java observations for the trusted Go consumer; never a Go state writer.
CREATE TABLE code_delivery.vn_t14_callbacks (
    callback_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES code_delivery.vn_t14_operations(operation_id),
    revision bigint NOT NULL CHECK (revision > 0),
    payload_json text NOT NULL CHECK (octet_length(payload_json) BETWEEN 1 AND 16384),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    acknowledged boolean NOT NULL DEFAULT false,
    UNIQUE (operation_id, revision)
);

-- Defense in depth against accidental CAS bypass or reversal of observed remote facts.
CREATE FUNCTION code_delivery.vn_t14_guard_operation_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision <> OLD.revision + 1
        OR (OLD.push_claimed AND NOT NEW.push_claimed)
        OR (OLD.mr_claimed AND NOT NEW.mr_claimed)
        OR (OLD.residual_race AND NOT NEW.residual_race)
        -- An unclaimed collision BLOCK is terminal for this operation.
        -- Neither a state reset nor a direct claim may rearm it.
        OR (OLD.push_state = 'BLOCKED' AND NOT OLD.push_claimed
            AND (NEW.push_state <> 'BLOCKED' OR NEW.push_claimed))
        OR (OLD.mr_state = 'BLOCKED' AND NOT OLD.mr_claimed
            AND (NEW.mr_state <> 'BLOCKED' OR NEW.mr_claimed))
        OR (OLD.last_confirmed_push_head IS NOT NULL
            AND NEW.last_confirmed_push_head IS DISTINCT FROM OLD.last_confirmed_push_head)
    THEN
        RAISE EXCEPTION 'VN-T14 durable history cannot be reversed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vn_t14_guard_operation_update
BEFORE UPDATE ON code_delivery.vn_t14_operations
FOR EACH ROW EXECUTE FUNCTION code_delivery.vn_t14_guard_operation_update();

CREATE FUNCTION code_delivery.vn_t14_guard_callback_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.acknowledged AND NOT NEW.acknowledged THEN
        RAISE EXCEPTION 'VN-T14 callback acknowledgement cannot be reversed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER vn_t14_guard_callback_update
BEFORE UPDATE ON code_delivery.vn_t14_callbacks
FOR EACH ROW EXECUTE FUNCTION code_delivery.vn_t14_guard_callback_update();

-- The runtime must not mutate immutable request data, confirmed MR history or callback payloads.
REVOKE ALL ON code_delivery.vn_t14_operations, code_delivery.vn_t14_observed_mrs,
    code_delivery.vn_t14_callbacks FROM PUBLIC;
GRANT SELECT, INSERT ON code_delivery.vn_t14_operations, code_delivery.vn_t14_observed_mrs,
    code_delivery.vn_t14_callbacks TO asep_delivery_runtime;
GRANT UPDATE (push_state, mr_state, push_claimed, mr_claimed, last_confirmed_push_head,
    residual_race, revision, updated_at) ON code_delivery.vn_t14_operations TO asep_delivery_runtime;
GRANT UPDATE (acknowledged) ON code_delivery.vn_t14_callbacks TO asep_delivery_runtime;
REVOKE ALL ON FUNCTION code_delivery.vn_t14_guard_operation_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION code_delivery.vn_t14_guard_callback_update() FROM PUBLIC;
-- No DELETE, DDL, migration history, request/identity rewrite or cross-schema access.
