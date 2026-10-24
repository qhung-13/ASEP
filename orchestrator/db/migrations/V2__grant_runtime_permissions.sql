-- Runtime gets application DML, never DDL, migration-history writes or other schemas.
GRANT USAGE ON SCHEMA orchestration TO asep_orch_runtime;
GRANT SELECT, INSERT, UPDATE ON orchestration.workflow_runs,
    orchestration.stage_attempts,
    orchestration.task_deliveries,
    orchestration.workflow_transitions,
    orchestration.workflow_outbox TO asep_orch_runtime;
-- Every future migration must explicitly grant only the new runtime permissions.
