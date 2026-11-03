package workflow

import "context"

// Sanitized owning-domain projection. Java separately authorizes its caller and
// joins the independently loaded Java delivery outcome; this is never authority.
type SliceObservation struct {
	RunID               string   `json:"runId"`
	SubjectKey          string   `json:"subjectKey"`
	PolicyID            string   `json:"policyId"`
	PolicyEpoch         int64    `json:"policyEpoch"`
	SourceFingerprint   string   `json:"sourceFingerprint"`
	State               string   `json:"state"`
	RunVersion          int64    `json:"runVersion"`
	BudgetLimit         int      `json:"budgetLimit"`
	BudgetUsed          int      `json:"budgetUsed"`
	ModelCallsReserved  int      `json:"modelCallsReserved"`
	ModelTokensReserved int      `json:"modelTokensReserved"`
	RemainingSeconds    int      `json:"remainingSeconds"`
	Corrections         int      `json:"corrections"`
	EvidenceRefs        []string `json:"evidenceRefs"`
}

func (s *Store) ObserveSyntheticSlice(ctx context.Context, run, prefix string) (SliceObservation, error) {
	if !canonicalUUID(run) || !syntheticPrefixPattern.MatchString(prefix) {
		return SliceObservation{}, ErrRunOutsideSyntheticScope
	}
	var r SliceObservation
	err := s.db.QueryRowContext(ctx, `SELECT r.run_id::text,r.subject_key,r.policy_id,r.policy_epoch,r.source_fingerprint_sha256,r.state,r.run_version,r.budget_limit,r.budget_used,p.model_calls_reserved,p.model_tokens_reserved,greatest(0,floor(extract(epoch from p.expires_at-clock_timestamp())))::int,
 (SELECT count(*) FROM orchestration.workflow_retry_receipts x WHERE x.run_id=r.run_id AND x.kind='REWORK')
 FROM orchestration.workflow_runs r JOIN orchestration.workflow_slice_profiles p ON p.run_id=r.run_id WHERE r.run_id=$1::uuid AND p.scope_prefix=$2 AND r.subject_key LIKE p.scope_prefix || ':%'`, run, prefix).Scan(&r.RunID, &r.SubjectKey, &r.PolicyID, &r.PolicyEpoch, &r.SourceFingerprint, &r.State, &r.RunVersion, &r.BudgetLimit, &r.BudgetUsed, &r.ModelCallsReserved, &r.ModelTokensReserved, &r.RemainingSeconds, &r.Corrections)
	if err != nil {
		return SliceObservation{}, ErrRunOutsideSyntheticScope
	}
	rows, err := s.db.QueryContext(ctx, `SELECT evidence_ref FROM orchestration.workflow_transitions WHERE run_id=$1::uuid AND evidence_ref LIKE 'synthetic:%' ORDER BY prior_run_version LIMIT 32`, run)
	if err != nil {
		return SliceObservation{}, err
	}
	defer rows.Close()
	r.EvidenceRefs = []string{}
	for rows.Next() {
		var ref string
		if err = rows.Scan(&ref); err != nil {
			return SliceObservation{}, err
		}
		r.EvidenceRefs = append(r.EvidenceRefs, ref)
	}
	return r, rows.Err()
}
