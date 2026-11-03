package workflow

// This connector is explicitly synthetic. It reuses the Go-owned run, attempt,
// lease, transition, result receipt and outbox; it cannot issue live enrollment
// or an Owner grant. There is no public/untrusted route to these methods.
import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

var ErrSliceBudget = errors.New("synthetic workflow budget exhausted")

type SliceLimits struct {
	ScopePrefix                                        string
	ElapsedSeconds, ModelTokenLimit, MaximumModelCalls int
}

func (l SliceLimits) valid() bool {
	return syntheticPrefixPattern.MatchString(l.ScopePrefix) && l.ElapsedSeconds >= 1 && l.ElapsedSeconds <= 300 && l.ModelTokenLimit >= 1 && l.ModelTokenLimit <= 4096 && l.MaximumModelCalls >= 1 && l.MaximumModelCalls <= 8
}
func insertSliceProfile(ctx context.Context, tx *sql.Tx, run string, l SliceLimits) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_slice_profiles(run_id,scope_prefix,expires_at,model_token_limit,maximum_model_calls)
 VALUES($1::uuid,$2,clock_timestamp()+($3::int*interval '1 second'),$4,$5)`, run, l.ScopePrefix, l.ElapsedSeconds, l.ModelTokenLimit, l.MaximumModelCalls)
	return err
}

// AdmitSyntheticSlice accepts only Java-authorized synthetic commands. Its scope
// and finite profile are fixed before task creation in the SAME admission TX.
func (s *Store) AdmitSyntheticSlice(ctx context.Context, c Admission, l SliceLimits) (AdmissionReceipt, error) {
	if !l.valid() || !strings.HasPrefix(c.SubjectKey, l.ScopePrefix+":") || !strings.HasPrefix(c.PolicyID, "synthetic-") || c.GitLabProjectID != "87786140" || c.TargetBranch != "main" {
		return AdmissionReceipt{}, ErrTransitionRejected
	}
	p := syntheticProfile{DeliveryLimit: 2, ReworkLimit: 1, ReplanLimit: 0, PolicyGranted: true}
	r, err := s.admitConfigured(ctx, c, nil, &p, "INVESTIGATING", "MANUAL", &l)
	if err != nil {
		return r, err
	}
	// Replayed admission must match original immutable limits; never refresh expiry.
	var prefix string
	var tokens, calls int
	var duration float64
	err = s.db.QueryRowContext(ctx, `SELECT scope_prefix,model_token_limit,maximum_model_calls,extract(epoch from expires_at-created_at) FROM orchestration.workflow_slice_profiles WHERE run_id=$1::uuid`, r.RunID).Scan(&prefix, &tokens, &calls, &duration)
	if err != nil || prefix != l.ScopePrefix || tokens != l.ModelTokenLimit || calls != l.MaximumModelCalls || duration > float64(l.ElapsedSeconds)+1 || duration < float64(l.ElapsedSeconds)-1 {
		return AdmissionReceipt{}, ErrConflict
	}
	return r, nil
}

type AgentTaskV1 struct {
	Schema            string `json:"schema"`
	RunID             string `json:"runId"`
	TaskID            string `json:"taskId"`
	AttemptID         string `json:"attemptId"`
	RunVersion        int64  `json:"runVersion"`
	LeaseEpoch        int64  `json:"leaseEpoch"`
	PolicyID          string `json:"policyId"`
	PolicyEpoch       int64  `json:"policyEpoch"`
	SourceFingerprint string `json:"sourceFingerprint"`
	BaseSHA           string `json:"baseSha"`
	Stage             string `json:"stage"`
	TokenReservation  int    `json:"tokenReservation"`
	RemainingSeconds  int    `json:"remainingSeconds"`
	OperationID       string `json:"operationId"`
	Scenario          string `json:"scenario"`
}

// A reservation is consumed exactly once. A caller receiving uncertainty must
// not execute another model call under that lease. Redelivery gets a new epoch
// and consumes another durable allowance before any child is dispatched.
func (s *Store) ReserveSliceAgentTask(ctx context.Context, l TaskLease, operation, scenario string) (AgentTaskV1, error) {
	if !canonicalUUID(operation) || (scenario != "success" && scenario != "bad-candidate" && scenario != "model-unavailable") || (l.Stage != "INVESTIGATING" && l.Stage != "PLANNING" && l.Stage != "IMPLEMENTING") {
		return AgentTaskV1{}, ErrTransitionRejected
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return AgentTaskV1{}, err
	}
	defer tx.Rollback()
	task := AgentTaskV1{Schema: "asep.agent.v1", RunID: l.RunID, TaskID: l.TaskID, AttemptID: l.AttemptID, Stage: l.Stage, LeaseEpoch: l.LeaseEpoch, TokenReservation: 128, OperationID: operation, Scenario: scenario}
	var state, prefix, command string
	err = tx.QueryRowContext(ctx, `SELECT state,run_version,policy_id,policy_epoch,source_fingerprint_sha256,repository_base_sha,subject_key,command_id::text
 FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, l.RunID).Scan(&state, &task.RunVersion, &task.PolicyID, &task.PolicyEpoch, &task.SourceFingerprint, &task.BaseSHA, &prefix, &command)
	if err != nil || state != l.Stage || command != operation {
		return AgentTaskV1{}, ErrTransitionRejected
	}
	if err = checkBoundedPolicy(ctx, tx, l.RunID, task.PolicyEpoch); err != nil {
		return AgentTaskV1{}, err
	}
	if _, err = lockDeliveryPolicy(ctx, tx, task.PolicyID, task.PolicyEpoch); err != nil {
		return AgentTaskV1{}, err
	}
	var eligible bool
	err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid AND status='LEASED' AND claimant_id=$4 AND lease_epoch=$5 AND lease_expires_at>clock_timestamp())`, l.TaskID, l.RunID, l.AttemptID, l.ClaimantID, l.LeaseEpoch).Scan(&eligible)
	if err != nil || !eligible {
		return AgentTaskV1{}, ErrLeaseLost
	}
	err = tx.QueryRowContext(ctx, `UPDATE orchestration.workflow_slice_profiles SET model_calls_reserved=model_calls_reserved+1,model_tokens_reserved=model_tokens_reserved+128
 WHERE run_id=$1::uuid AND $2 LIKE scope_prefix || ':%' AND expires_at>clock_timestamp()
 AND model_calls_reserved<maximum_model_calls AND model_tokens_reserved+128<=model_token_limit
 RETURNING greatest(1,floor(extract(epoch from expires_at-clock_timestamp())))::int`, l.RunID, prefix).Scan(&task.RemainingSeconds)
	if errors.Is(err, sql.ErrNoRows) {
		return AgentTaskV1{}, ErrSliceBudget
	}
	if err != nil {
		return AgentTaskV1{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_slice_reservations(task_id,run_id,attempt_id,lease_epoch,tokens) VALUES($1::uuid,$2::uuid,$3::uuid,$4,128)`, l.TaskID, l.RunID, l.AttemptID, l.LeaseEpoch)
	if err != nil {
		return AgentTaskV1{}, ErrResultConflict
	}
	if err = tx.Commit(); err != nil {
		return AgentTaskV1{}, &resultUnknownError{err}
	}
	return task, nil
}

type SliceAdvance struct {
	OperationID                     string
	Lease                           TaskLease
	ExpectedRunVersion, PolicyEpoch int64
	SourceFingerprint, EvidenceRef  string
	SealedCandidateEvidenceID       string `json:",omitempty"`
}

// AdvanceSyntheticSlice never accepts caller-selected successor states or a
// worker "tests passed" field. Verification is accepted only from the existing
// protected candidate-evidence table, independently issued by the authority role.
func (s *Store) AdvanceSyntheticSlice(ctx context.Context, c SliceAdvance) (advanceReceipt, error) {
	next := map[string]string{"INVESTIGATING": "PLANNING", "PLANNING": "IMPLEMENTING", "IMPLEMENTING": "VERIFYING", "VERIFYING": "REVIEWING"}[c.Lease.Stage]
	if next == "" || !canonicalUUID(c.OperationID) || !isLowerHex(c.SourceFingerprint, 64) || !strings.HasPrefix(c.EvidenceRef, "synthetic:") || len(c.EvidenceRef) > 256 {
		return advanceReceipt{}, ErrTransitionRejected
	}
	digest, err := sliceAdvanceDigest(c)
	if err != nil {
		return advanceReceipt{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return advanceReceipt{}, err
	}
	defer tx.Rollback()
	if prior, e := queryResult(ctx, tx, c.OperationID, digest); e == nil {
		return prior, nil
	} else if !errors.Is(e, sql.ErrNoRows) {
		return advanceReceipt{}, e
	}
	var state, source, policy, subject string
	var version, epoch int64
	err = tx.QueryRowContext(ctx, `SELECT state,run_version,policy_epoch,source_fingerprint_sha256,policy_id,subject_key FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, c.Lease.RunID).Scan(&state, &version, &epoch, &source, &policy, &subject)
	if err != nil {
		return advanceReceipt{}, err
	}
	// A concurrent exact replay may have committed while we waited for the run lock.
	if prior, e := queryResult(ctx, tx, c.OperationID, digest); e == nil {
		return prior, nil
	} else if !errors.Is(e, sql.ErrNoRows) {
		return advanceReceipt{}, e
	}
	if err != nil || state != c.Lease.Stage || version != c.ExpectedRunVersion || epoch != c.PolicyEpoch || source != c.SourceFingerprint {
		return advanceReceipt{}, ErrTransitionRejected
	}
	if err = checkBoundedPolicy(ctx, tx, c.Lease.RunID, epoch); err != nil {
		return advanceReceipt{}, err
	}
	if _, err = lockDeliveryPolicy(ctx, tx, policy, epoch); err != nil {
		return advanceReceipt{}, err
	}
	var fresh bool
	err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.workflow_slice_profiles WHERE run_id=$1::uuid AND $2 LIKE scope_prefix || ':%' AND expires_at>clock_timestamp())`, c.Lease.RunID, subject).Scan(&fresh)
	if err != nil || !fresh {
		return advanceReceipt{}, ErrSliceBudget
	}
	artifactRef := c.EvidenceRef
	if c.Lease.Stage == "IMPLEMENTING" {
		if err = s.requireSliceCandidateSeal(ctx, tx, c, version, policy, epoch); err != nil {
			return advanceReceipt{}, err
		}
		artifactRef = "synthetic:candidate:" + c.SealedCandidateEvidenceID
	} else if c.SealedCandidateEvidenceID != "" {
		return advanceReceipt{}, ErrTransitionRejected
	}
	candidateID := ""
	if c.Lease.Stage != "VERIFYING" {
		err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.workflow_slice_reservations WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid AND lease_epoch=$4)`, c.Lease.TaskID, c.Lease.RunID, c.Lease.AttemptID, c.Lease.LeaseEpoch).Scan(&fresh)
		if err != nil || !fresh {
			return advanceReceipt{}, ErrTransitionRejected
		}
	} else {
		id := strings.TrimPrefix(c.EvidenceRef, "synthetic:candidate:")
		candidateID = id
		if !canonicalUUID(id) || c.Lease.ClaimantID != ControllerClaimant {
			return advanceReceipt{}, ErrDeliveryDenied
		}
		err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.controlled_candidate_evidence e JOIN orchestration.stage_attempts a ON a.attempt_id=e.producer_attempt_id AND a.run_id=e.run_id WHERE e.evidence_id=$1::uuid AND e.run_id=$2::uuid AND e.revoked_at IS NULL AND e.expires_at>clock_timestamp() AND a.stage='IMPLEMENTING' AND a.state='SUCCEEDED' AND a.output_artifact_ref='synthetic:candidate:' || e.evidence_id::text AND (e.request_json->>'runVersion')::bigint=$3)`, id, c.Lease.RunID, version+2).Scan(&fresh)
		if err != nil || !fresh {
			return advanceReceipt{}, ErrDeliveryDenied
		}
	}
	result, err := tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status='COMPLETED',updated_at=clock_timestamp() WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid AND stage=$4 AND claimant_id=$5 AND lease_epoch=$6 AND status='LEASED' AND lease_expires_at>clock_timestamp()`, c.Lease.TaskID, c.Lease.RunID, c.Lease.AttemptID, state, c.Lease.ClaimantID, c.Lease.LeaseEpoch)
	if e := oneCancellationRow(result, err); e != nil {
		return advanceReceipt{}, e
	}
	result, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='SUCCEEDED',output_artifact_ref=$3,completed_at=clock_timestamp() WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND state='RUNNING'`, c.Lease.AttemptID, c.Lease.RunID, artifactRef)
	if e := oneCancellationRow(result, err); e != nil {
		return advanceReceipt{}, e
	}
	var attempt, task, transition, message string
	for _, id := range []*string{&attempt, &task, &transition, &message} {
		*id, err = freshUUID()
		if err != nil {
			return advanceReceipt{}, err
		}
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no) SELECT $1::uuid,$2::uuid,$3::varchar,coalesce(max(semantic_attempt_no),0)+1 FROM orchestration.stage_attempts WHERE run_id=$2::uuid AND stage=$3::varchar`, attempt, c.Lease.RunID, next)
	if err != nil {
		return advanceReceipt{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.task_deliveries(task_id,run_id,attempt_id,stage) VALUES($1::uuid,$2::uuid,$3::uuid,$4)`, task, c.Lease.RunID, attempt, next)
	if err != nil {
		return advanceReceipt{}, err
	}
	if candidateID != "" {
		// Verification-to-review is still one Go-local transaction. The new
		// review task belongs to the protected controller, never the agent.
		_, err = tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status='LEASED',claimant_id=$2,lease_epoch=1,delivery_count=1,lease_expires_at=LEAST((SELECT expires_at FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$3::uuid),clock_timestamp()+interval '120 seconds') WHERE task_id=$1::uuid`, task, ControllerClaimant, candidateID)
		if err != nil {
			return advanceReceipt{}, err
		}
		_, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='RUNNING' WHERE attempt_id=$1::uuid`, attempt)
		if err != nil {
			return advanceReceipt{}, err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.controlled_review_receipts(evidence_id,run_id,request_hash,review_attempt_id,review_task_id,review_lease_epoch) SELECT evidence_id,run_id,request_hash,$2::uuid,$3::uuid,1 FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$1::uuid AND run_id=$4::uuid`, candidateID, attempt, task, c.Lease.RunID)
		if err != nil {
			return advanceReceipt{}, err
		}
	}
	_, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state=$2,run_version=run_version+1,updated_at=clock_timestamp() WHERE run_id=$1::uuid`, c.Lease.RunID, next)
	if err != nil {
		return advanceReceipt{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_transitions(transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref) VALUES($1::uuid,$2::uuid,$3::uuid,$4,$5,$6,$7)`, transition, c.Lease.RunID, c.Lease.AttemptID, state, next, version, artifactRef)
	if err != nil {
		return advanceReceipt{}, err
	}
	event := map[string]any{"schema": "asep.stage.v1", "runId": c.Lease.RunID, "taskId": task, "attemptId": attempt, "stage": next, "runVersion": version + 1, "policyEpoch": epoch, "sourceFingerprint": source}
	if c.SealedCandidateEvidenceID != "" {
		event["sealedCandidateEvidenceId"] = c.SealedCandidateEvidenceID
	}
	payload, _ := json.Marshal(event)
	payloadHash, _ := privateDigest(json.RawMessage(payload))
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload) VALUES($1::uuid,$2::uuid,'STAGE_TASK_QUEUED',$3,$4::jsonb)`, message, c.Lease.RunID, payloadHash, string(payload))
	if err != nil {
		return advanceReceipt{}, err
	}
	var accepted time.Time
	err = tx.QueryRowContext(ctx, `INSERT INTO orchestration.workflow_result_receipts(operation_id,digest_version,payload_sha256,run_id,task_id,attempt_id,claimant_id,lease_epoch,expected_run_version,policy_epoch,source_fingerprint_sha256,transition_id,next_task_id,next_attempt_id,outbox_message_id,accepted_run_version,accepted_at)
 VALUES($1::uuid,1,$2,$3::uuid,$4::uuid,$5::uuid,$6,$7,$8,$9,$10,$11::uuid,$12::uuid,$13::uuid,$14::uuid,$15,clock_timestamp()) RETURNING accepted_at`, c.OperationID, digest, c.Lease.RunID, c.Lease.TaskID, c.Lease.AttemptID, c.Lease.ClaimantID, c.Lease.LeaseEpoch, version, epoch, source, transition, task, attempt, message, version+1).Scan(&accepted)
	if err != nil {
		return advanceReceipt{}, err
	}
	if err = tx.Commit(); err != nil {
		return s.ReconcileSyntheticSlice(ctx, c)
	}
	return advanceReceipt{OperationID: c.OperationID, DigestVersion: 1, PayloadSHA256: digest, RunID: c.Lease.RunID, PreviousTaskID: c.Lease.TaskID, PreviousAttemptID: c.Lease.AttemptID, ClaimantID: c.Lease.ClaimantID, LeaseEpoch: c.Lease.LeaseEpoch, ExpectedRunVersion: version, PolicyEpoch: epoch, SourceFingerprintSHA256: source, NextAttemptID: attempt, NextTaskID: task, TransitionID: transition, OutboxMessageID: message, RunVersion: version + 1, AcceptedAt: accepted}, nil
}

// Mutable lease expiry/delivery count are transport metadata, not logical-result identity.
func sliceAdvanceDigest(c SliceAdvance) (string, error) {
	c.Lease.ExpiresAt = time.Time{}
	c.Lease.DeliveryCount = 0
	return privateDigest(struct {
		Domain  string
		Command SliceAdvance
	}{"asep.slice.advance.v1", c})
}

// Reconciliation reads only the original result receipt; absence remains UNKNOWN.
func (s *Store) ReconcileSyntheticSlice(ctx context.Context, c SliceAdvance) (advanceReceipt, error) {
	digest, err := sliceAdvanceDigest(c)
	if err != nil {
		return advanceReceipt{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return advanceReceipt{}, &resultUnknownError{err}
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(0)
	r, err := queryResult(ctx, db, c.OperationID, digest)
	if err == nil || errors.Is(err, ErrResultConflict) {
		return r, err
	}
	return advanceReceipt{}, &resultUnknownError{err}
}

// CorrectSyntheticSlice consumes the existing retry engine's finite REWORK
// allowance. Only a trusted supervisor's protected-test failure decision belongs
// here; agent-reported tests or a model recommendation are not such evidence.
func (s *Store) CorrectSyntheticSlice(ctx context.Context, c SliceAdvance) (retryReceipt, error) {
	if (c.Lease.Stage != "IMPLEMENTING" && c.Lease.Stage != "VERIFYING") || !strings.HasPrefix(c.EvidenceRef, "synthetic:vn-t10-it-") {
		return retryReceipt{}, ErrTransitionRejected
	}
	decision := retryDecision{sliceCorrection: true, OperationID: c.OperationID, Kind: "REWORK", Lease: c.Lease, ExpectedRunVersion: c.ExpectedRunVersion, PolicyEpoch: c.PolicyEpoch, SourceSHA256: c.SourceFingerprint, EvidenceRef: c.EvidenceRef}
	if err := s.authorizeSyntheticRetry(ctx, decision); err != nil {
		return retryReceipt{}, err
	}
	return s.decideRetry(ctx, decision)
}

// AdvanceSliceReview derives the current verification lease from Go-owned state.
// It is called only behind the protected controller mTLS route, after the
// independent issuer validates the real candidate/test receipts.
func (s *Store) AdvanceSliceReview(ctx context.Context, id string) (ReviewReceipt, error) {
	if !canonicalUUID(id) {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	prior := ReviewReceipt{EvidenceID: id}
	err := s.db.QueryRowContext(ctx, `SELECT run_id::text,review_attempt_id::text,review_task_id::text,request_hash FROM orchestration.controlled_review_receipts WHERE evidence_id=$1::uuid`, id).Scan(&prior.RunID, &prior.ReviewAttemptID, &prior.ReviewTaskID, &prior.RequestHash)
	if err == nil {
		return prior, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return ReviewReceipt{}, err
	}
	c := SliceAdvance{OperationID: id, EvidenceRef: "synthetic:candidate:" + id}
	err = s.db.QueryRowContext(ctx, `SELECT r.run_id::text,r.run_version,r.policy_epoch,r.source_fingerprint_sha256,t.task_id::text,t.attempt_id::text,t.stage,t.claimant_id,t.lease_epoch,t.delivery_count,t.lease_expires_at
 FROM orchestration.controlled_candidate_evidence e JOIN orchestration.workflow_runs r ON r.run_id=e.run_id JOIN orchestration.workflow_slice_profiles p ON p.run_id=r.run_id JOIN orchestration.task_deliveries t ON t.run_id=r.run_id AND t.stage='VERIFYING'
 WHERE e.evidence_id=$1::uuid AND r.state='VERIFYING' AND r.subject_key LIKE p.scope_prefix || ':%' AND p.expires_at>clock_timestamp() AND e.revoked_at IS NULL AND e.expires_at>clock_timestamp() AND t.status='LEASED' AND t.claimant_id=$2 AND t.lease_expires_at>clock_timestamp()`, id, ControllerClaimant).Scan(&c.Lease.RunID, &c.ExpectedRunVersion, &c.PolicyEpoch, &c.SourceFingerprint, &c.Lease.TaskID, &c.Lease.AttemptID, &c.Lease.Stage, &c.Lease.ClaimantID, &c.Lease.LeaseEpoch, &c.Lease.DeliveryCount, &c.Lease.ExpiresAt)
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	if _, err = s.AdvanceSyntheticSlice(ctx, c); err != nil {
		return ReviewReceipt{}, err
	}
	err = s.db.QueryRowContext(ctx, `SELECT run_id::text,review_attempt_id::text,review_task_id::text,request_hash FROM orchestration.controlled_review_receipts WHERE evidence_id=$1::uuid`, id).Scan(&prior.RunID, &prior.ReviewAttemptID, &prior.ReviewTaskID, &prior.RequestHash)
	return prior, err
}

// A reference in a worker result is never authority. Only the existing protected
// issuer table is eligible: its INSERT provenance and immutable contents are
// enforced by V12 ACLs/trigger and the controller mTLS boundary. Hold the row while
// committing the handoff so revocation cannot race the durable success decision.
func (s *Store) requireSliceCandidateSeal(ctx context.Context, tx *sql.Tx, c SliceAdvance, version int64, policy string, epoch int64) error {
	if !canonicalUUID(c.SealedCandidateEvidenceID) || c.Lease.ClaimantID != ControllerClaimant {
		return ErrDeliveryDenied
	}
	var body, sealBody []byte
	var requestHash, base, target, command string
	var now time.Time
	err := tx.QueryRowContext(ctx, `SELECT e.request_json,e.seal_json,e.request_hash,r.repository_base_sha,r.observed_target_head_sha,r.command_id::text,clock_timestamp()
 FROM orchestration.controlled_candidate_evidence e JOIN orchestration.workflow_runs r ON r.run_id=e.run_id
 WHERE e.evidence_id=$1::uuid AND e.run_id=$2::uuid AND e.producer_attempt_id=$3::uuid AND e.producer_task_id=$4::uuid AND e.claimant_id=$5 AND e.lease_epoch=$6
 AND e.revoked_at IS NULL AND e.expires_at>clock_timestamp() AND pg_has_role(e.issued_by,'asep_orch_authority','USAGE') FOR SHARE OF e`, c.SealedCandidateEvidenceID, c.Lease.RunID, c.Lease.AttemptID, c.Lease.TaskID, ControllerClaimant, c.Lease.LeaseEpoch).Scan(&body, &sealBody, &requestHash, &base, &target, &command, &now)
	if err != nil {
		return ErrDeliveryDenied
	}
	r, err := decodeDeliveryRequest(body)
	if err != nil {
		return ErrDeliveryDenied
	}
	hash, err := r.Hash()
	if err != nil || hash != requestHash || r.OperationID != command || r.RunID != c.Lease.RunID || r.AttemptID != c.Lease.AttemptID || r.RunVersion != version+3 || r.PolicyID != policy || r.PolicyEpoch != epoch || r.BaseSHA != base || r.ExpectedTargetSHA != target {
		return ErrDeliveryDenied
	}
	var seal CandidateSeal
	if json.Unmarshal(sealBody, &seal) != nil || seal.ReceiptID != r.SealReceiptID || seal.RunID != r.RunID || seal.AttemptID != r.AttemptID || seal.BaseSHA != base || seal.CommitSHA != r.CandidateCommitSHA || seal.TreeSHA != r.CandidateTreeSHA || seal.ManifestSHA256 != r.ManifestSHA256 || seal.WriterStoppedAt.IsZero() || seal.WriterStoppedAt.After(seal.SealedAt) || seal.SealedAt.After(now) || !seal.ExpiresAt.After(now) {
		return ErrDeliveryDenied
	}
	return nil
}
