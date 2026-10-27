REVOKE ALL ON SCHEMA code_delivery FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA code_delivery FROM PUBLIC;
GRANT USAGE ON SCHEMA code_delivery TO asep_delivery_runtime;
GRANT SELECT, INSERT ON code_delivery.operations, code_delivery.callbacks TO asep_delivery_runtime;
GRANT UPDATE (push_state, mr_state, mr_iid, residual_race, reason, revision, updated_at)
    ON code_delivery.operations TO asep_delivery_runtime;
GRANT UPDATE (acknowledged) ON code_delivery.callbacks TO asep_delivery_runtime;
-- No DELETE, DDL, identity/body rewrite, migration ownership or cross-schema grants.
