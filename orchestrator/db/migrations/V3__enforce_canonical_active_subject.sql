-- VN10-CR-01: data-identity guard independent of caller-provided subject_key.
-- IDs are the trusted, exact stable Jira and GitLab identifiers at admission.
-- Migration fails closed if any existing active duplicate identity is present.
-- Existing V1/V2 checksums are unchanged.
CREATE UNIQUE INDEX uq_orch_one_active_canonical_subject
ON orchestration.workflow_runs (
    jira_site_id, jira_project_id, jira_issue_id, gitlab_project_id
)
WHERE state NOT IN (
    'COMPLETED_DRAFT_MR', 'BLOCKED', 'ESCALATED',
    'FAILED', 'CANCELLED', 'SUPERSEDED'
);

-- Stop treating the caller's scoping/display label as uniqueness authority.
-- PostgreSQL migration executes these DDL steps transactionally.
DROP INDEX orchestration.uq_orch_one_active_subject;
