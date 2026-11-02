-- Owner Grant authority for controlled Draft MR delivery.
-- Domain-level names; issuance remains restricted to the VN-T14 sandbox.
-- No live GitLab write authority is conferred by this migration.

CREATE TABLE code_delivery.owner_grants (
    grant_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    request_hash char(64) NOT NULL
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    project_id bigint NOT NULL CHECK (project_id = 87786140),
    repository_path varchar(160) NOT NULL
        CHECK (repository_path = 'qhung-13/asep-m01-sandbox'),
    source_branch varchar(80) NOT NULL,
    target_branch varchar(16) NOT NULL
        CHECK (target_branch = 'main'),

    issued_by varchar(128) NOT NULL
        CHECK (length(btrim(issued_by)) > 0),
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    not_before timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,

    revoked_at timestamptz,
    revoked_by varchar(128),

    CONSTRAINT owner_grant_source_scope CHECK (
        source_branch = 'test/vn-t14/' || operation_id::text
    ),
    CONSTRAINT owner_grant_valid_window CHECK (
        expires_at > not_before
        AND expires_at > issued_at
        AND expires_at <= issued_at + INTERVAL '1 hour'
    ),
    CONSTRAINT owner_grant_revocation_pair CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL)
        OR
        (revoked_at IS NOT NULL AND revoked_by IS NOT NULL
         AND length(btrim(revoked_by)) > 0
         AND revoked_at >= issued_at)
    )
);

CREATE TABLE code_delivery.owner_grant_events (
    event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    grant_id uuid NOT NULL
        REFERENCES code_delivery.owner_grants(grant_id),
    event_kind varchar(16) NOT NULL
        CHECK (event_kind IN ('ISSUED', 'REVOKED')),
    actor_id varchar(128) NOT NULL
        CHECK (length(btrim(actor_id)) > 0),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),

    UNIQUE (grant_id, event_kind)
);

-- No identity rewrite, expiry extension, revocation reversal,
-- or second revocation, including by accidental privileged SQL.
CREATE FUNCTION code_delivery.guard_owner_grant_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL
        OR NEW.revoked_at IS NULL
        OR NEW.revoked_by IS NULL
        OR length(btrim(NEW.revoked_by)) = 0
        OR ROW(
            NEW.grant_id, NEW.operation_id, NEW.request_hash,
            NEW.project_id, NEW.repository_path,
            NEW.source_branch, NEW.target_branch,
            NEW.issued_by, NEW.issued_at,
            NEW.not_before, NEW.expires_at
        ) IS DISTINCT FROM ROW(
            OLD.grant_id, OLD.operation_id, OLD.request_hash,
            OLD.project_id, OLD.repository_path,
            OLD.source_branch, OLD.target_branch,
            OLD.issued_by, OLD.issued_at,
            OLD.not_before, OLD.expires_at
        )
    THEN
        RAISE EXCEPTION 'owner grant history is immutable'
            USING ERRCODE = '23514';
    END IF;

    -- The database, not the caller, records the revocation instant.
    NEW.revoked_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER owner_grants_guard_update
BEFORE UPDATE ON code_delivery.owner_grants
FOR EACH ROW
EXECUTE FUNCTION code_delivery.guard_owner_grant_update();

-- Audit insertions share the grant transaction.
-- SECURITY DEFINER allows the trigger to append events without
-- granting the issuer direct write access to the audit table.
CREATE FUNCTION code_delivery.audit_owner_grant_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO code_delivery.owner_grant_events
            (grant_id, event_kind, actor_id)
        VALUES (NEW.grant_id, 'ISSUED', NEW.issued_by);
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO code_delivery.owner_grant_events
            (grant_id, event_kind, actor_id)
        VALUES (NEW.grant_id, 'REVOKED', NEW.revoked_by);
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER owner_grants_audit_change
AFTER INSERT OR UPDATE ON code_delivery.owner_grants
FOR EACH ROW
EXECUTE FUNCTION code_delivery.audit_owner_grant_change();

REVOKE ALL ON code_delivery.owner_grants,
    code_delivery.owner_grant_events FROM PUBLIC;

REVOKE ALL ON FUNCTION
    code_delivery.guard_owner_grant_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION
    code_delivery.audit_owner_grant_change() FROM PUBLIC;

-- Delivery workers may inspect authority, never create it.
GRANT SELECT ON code_delivery.owner_grants
    TO asep_delivery_runtime;

-- Dedicated issuer credential, separate from delivery runtime.
GRANT USAGE ON SCHEMA code_delivery
    TO asep_delivery_issuer;

GRANT SELECT ON code_delivery.owner_grants,
    code_delivery.owner_grant_events
    TO asep_delivery_issuer;

-- Immutable fields can only be set during initial issuance.
GRANT INSERT (
    grant_id, operation_id, request_hash, project_id,
    repository_path, source_branch, target_branch,
    issued_by, not_before, expires_at
) ON code_delivery.owner_grants
    TO asep_delivery_issuer;

-- Issuer can only request revocation, never rewrite a grant.
GRANT UPDATE (revoked_at, revoked_by)
    ON code_delivery.owner_grants
    TO asep_delivery_issuer;

-- No DELETE, direct audit mutations, runtime issuance,
-- cross-schema permissions, or GitLab capabilities.
