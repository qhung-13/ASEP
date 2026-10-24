package workflow

import (
	"context"
	"database/sql"
	"errors"
	"regexp"
)

var ErrRunOutsideSyntheticScope = errors.New("run not found in allowed synthetic scope")

var runIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var syntheticPrefixPattern = regexp.MustCompile(`^synthetic:vn-t10-it-[0-9a-f]{32}$`)

// RunStatus contains strictly non-sensitive workflow diagnostics; it is not an
// authorization decision or an approval to advance a workflow.
type RunStatus struct {
	RunID         string `json:"run_id"`
	State         string `json:"state"`
	RunVersion    int64  `json:"run_version"`
	BudgetLimit   int    `json:"budget_limit"`
	BudgetUsed    int    `json:"budget_used"`
	TaskCount     int64  `json:"task_count"`
	AttemptCount  int64  `json:"attempt_count"`
	MaxLeaseEpoch int64  `json:"max_lease_epoch"`
}

// SyntheticRunStatus is a specifically scoped vNext prototype read, never a
// general authorization API. The trusted adapter must authorize both caller
// and scope; this query additionally enforces a single test-run prefix in SQL.
func (s *Store) SyntheticRunStatus(ctx context.Context, runID, prefix string) (RunStatus, error) {
	if !runIDPattern.MatchString(runID) || !syntheticPrefixPattern.MatchString(prefix) {
		return RunStatus{}, ErrRunOutsideSyntheticScope
	}
	var result RunStatus
	err := s.db.QueryRowContext(ctx, `
        SELECT r.run_id::text,r.state,r.run_version,r.budget_limit,r.budget_used,
            (SELECT count(*) FROM orchestration.task_deliveries t WHERE t.run_id=r.run_id),
            (SELECT count(*) FROM orchestration.stage_attempts a WHERE a.run_id=r.run_id),
            (SELECT coalesce(max(t.lease_epoch),0) FROM orchestration.task_deliveries t WHERE t.run_id=r.run_id)
        FROM orchestration.workflow_runs r
        WHERE r.run_id=$1::uuid AND r.subject_key LIKE $2`, runID, prefix+":%").Scan(
		&result.RunID, &result.State, &result.RunVersion, &result.BudgetLimit,
		&result.BudgetUsed, &result.TaskCount, &result.AttemptCount, &result.MaxLeaseEpoch)
	if errors.Is(err, sql.ErrNoRows) {
		return RunStatus{}, ErrRunOutsideSyntheticScope
	}
	if err != nil {
		return RunStatus{}, err
	}
	return result, nil
}
