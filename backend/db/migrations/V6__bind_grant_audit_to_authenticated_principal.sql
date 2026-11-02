-- Forward hardening: agent-supplied labels are not operator identities.
-- Preserve V1-V5 history. The trusted operator login must be separately isolated.
CREATE FUNCTION code_delivery.bind_owner_grant_principal()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.issued_by := session_user;
        NEW.issued_at := clock_timestamp();
    ELSE
        NEW.revoked_by := session_user;
    END IF;
    RETURN NEW;
END;
$$;
-- This name sorts before the existing immutable-update guard.
CREATE TRIGGER owner_grant_bind_principal
BEFORE INSERT OR UPDATE ON code_delivery.owner_grants
FOR EACH ROW EXECUTE FUNCTION code_delivery.bind_owner_grant_principal();
REVOKE ALL ON FUNCTION code_delivery.bind_owner_grant_principal() FROM PUBLIC;
ALTER FUNCTION code_delivery.guard_owner_grant_update()
    SET search_path = pg_catalog, pg_temp;
