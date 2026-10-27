-- Java-owned synthetic Code Delivery. Deliberately separate from orchestration and M01 Publication.
CREATE TABLE code_delivery.operations (
    operation_id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    repository_id varchar(19) NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    intent_json text NOT NULL CHECK (octet_length(intent_json) BETWEEN 1 AND 16384),
    push_state varchar(40) NOT NULL CHECK (push_state IN ('PLANNED','IN_PROGRESS','CONFIRMED','UNKNOWN','BLOCKED','MANUAL_RECONCILIATION_REQUIRED')),
    mr_state varchar(40) NOT NULL CHECK (mr_state IN ('PLANNED','IN_PROGRESS','CONFIRMED','UNKNOWN','BLOCKED','MANUAL_RECONCILIATION_REQUIRED')),
    mr_iid bigint CHECK (mr_iid > 0),
    residual_race boolean NOT NULL DEFAULT false,
    reason varchar(40) NOT NULL CHECK (reason IN ('NONE','PREREQUISITE','REMOTE_MISMATCH','REMOTE_UNCERTAIN','REMOTE_REJECTED','READ_REVOKED','POST_SEND_CHANGE')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (run_id, repository_id),
    CHECK ((mr_state='CONFIRMED') = (mr_iid IS NOT NULL)),
    CHECK (mr_state IN ('PLANNED','BLOCKED','MANUAL_RECONCILIATION_REQUIRED') OR push_state='CONFIRMED')
);
CREATE TABLE code_delivery.callbacks (
    callback_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES code_delivery.operations(operation_id),
    revision bigint NOT NULL CHECK (revision > 0),
    payload_json text NOT NULL CHECK (octet_length(payload_json) BETWEEN 1 AND 8192),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    acknowledged boolean NOT NULL DEFAULT false,
    UNIQUE (operation_id, revision)
);
