package workflow

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"strconv"
	"time"
)

const ControllerClaimant = "protected-candidate-controller"

type ControlledStart struct {
	OperationID string `json:"operationId"`
	PolicyID    string `json:"policyId"`
	PolicyEpoch int64  `json:"policyEpoch"`
	BaseSHA     string `json:"baseSha"`
}
type ControlledReceipt struct {
	RunID             string `json:"runId"`
	ProducerAttemptID string `json:"producerAttemptId"`
	TaskID            string `json:"taskId"`
	RunVersion        int64  `json:"runVersion"`
}

// AdmitControlledProbe is a separate, explicitly bounded controller entry. It does
// not mark investigation/planning or agent execution successful. Only a mutually
// authenticated protected controller may call this; there is no agent route.
func (s *Store) AdmitControlledProbe(ctx context.Context, c ControlledStart) (ControlledReceipt, error) {
	if !canonicalUUID(c.OperationID) || !isLowerHex(c.BaseSHA, 40) || c.PolicyEpoch < 1 {
		return ControlledReceipt{}, ErrDeliveryDenied
	}
	digest := sha256.Sum256([]byte("asep-controlled-probe-v1:" + c.OperationID + ":" + c.BaseSHA + ":" + c.PolicyID + ":" + strconv.FormatInt(c.PolicyEpoch, 10)))
	admission := Admission{CommandID: c.OperationID, SubjectKey: "controlled-probe:" + c.OperationID, ActorID: ControllerClaimant, JiraSiteID: "not-applicable-controller-probe", JiraProjectID: "not-applicable", JiraIssueID: c.OperationID, GitLabProjectID: "87786140", RepositoryBaseSHA: c.BaseSHA, TargetBranch: "main", ObservedTargetHeadSHA: c.BaseSHA, SourceFingerprintSHA256: hex.EncodeToString(digest[:]), PolicyID: c.PolicyID, PolicyEpoch: c.PolicyEpoch, BudgetLimit: 1}
	receipt, err := s.admitAt(ctx, admission, nil, nil, "IMPLEMENTING", "CONTROLLER_PROBE")
	if err != nil {
		return ControlledReceipt{}, ErrDeliveryDenied
	}
	return ControlledReceipt{receipt.RunID, receipt.AttemptID, receipt.TaskID, 1}, nil
}
func (s *Store) ClaimControlledProbe(ctx context.Context, run string) (TaskLease, error) {
	var eligible bool
	if !canonicalUUID(run) || s.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.workflow_runs WHERE run_id=$1::uuid AND trigger_kind='CONTROLLER_PROBE' AND state='IMPLEMENTING' AND run_version=1)`, run).Scan(&eligible) != nil || !eligible {
		return TaskLease{}, ErrDeliveryDenied
	}
	return s.ClaimForRun(ctx, run, ControllerClaimant, 120)
}

type CandidateSeal struct {
	ReceiptID       string    `json:"receiptId"`
	RunID           string    `json:"runId"`
	AttemptID       string    `json:"attemptId"`
	BaseSHA         string    `json:"baseSha"`
	CommitSHA       string    `json:"commitSha"`
	TreeSHA         string    `json:"treeSha"`
	ManifestSHA256  string    `json:"manifestSha256"`
	WriterStoppedAt time.Time `json:"writerStoppedAt"`
	SealedAt        time.Time `json:"sealedAt"`
	ExpiresAt       time.Time `json:"expiresAt"`
}
type CandidateVerification struct {
	ReceiptID      string    `json:"receiptId"`
	RunID          string    `json:"runId"`
	AttemptID      string    `json:"attemptId"`
	SealReceiptID  string    `json:"sealReceiptId"`
	ManifestSHA256 string    `json:"manifestSha256"`
	ProfileSHA256  string    `json:"profileSha256"`
	HarnessSHA256  string    `json:"harnessSha256"`
	ImageSHA256    string    `json:"imageSha256"`
	LogsSHA256     string    `json:"logsSha256"`
	Discovered     int       `json:"discovered"`
	Executed       int       `json:"executed"`
	Skipped        int       `json:"skipped"`
	Failed         int       `json:"failed"`
	ExitCode       int       `json:"exitCode"`
	TimedOut       bool      `json:"timedOut"`
	IssuedAt       time.Time `json:"issuedAt"`
	ExpiresAt      time.Time `json:"expiresAt"`
}
type ControlledSubmission struct {
	EvidenceID     string                `json:"evidenceId"`
	ProducerTaskID string                `json:"producerTaskId"`
	LeaseEpoch     int64                 `json:"leaseEpoch"`
	Request        DeliveryRequest       `json:"request"`
	Seal           CandidateSeal         `json:"seal"`
	Verification   CandidateVerification `json:"verification"`
}
type ReviewReceipt struct {
	EvidenceID      string `json:"evidenceId"`
	RunID           string `json:"runId"`
	ReviewAttemptID string `json:"reviewAttemptId"`
	ReviewTaskID    string `json:"reviewTaskId"`
	RequestHash     string `json:"requestHash"`
}

// ReviewIssuer is a separate protected role capability. Delivery workers cannot
// instantiate it with their runtime credential. Incoming proof is accepted only
// over the controller mTLS route; no request flag establishes its provenance.
type ReviewIssuer struct{ db *sql.DB }

func OpenReviewIssuer(ctx context.Context, databaseURL string) (*ReviewIssuer, error) {
	db, err := sql.Open("pgx", databaseURL)
	if err != nil {
		return nil, ErrDeliveryDenied
	}
	db.SetMaxOpenConns(4)
	var allowed bool
	err = db.QueryRowContext(ctx, `SELECT pg_has_role(session_user,'asep_orch_authority','USAGE') AND session_user=current_user AND NOT(rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls) FROM pg_roles WHERE rolname=session_user`).Scan(&allowed)
	if err != nil || !allowed {
		db.Close()
		return nil, ErrDeliveryDenied
	}
	return &ReviewIssuer{db}, nil
}
func (i *ReviewIssuer) Close() error { return i.db.Close() }
func (i *ReviewIssuer) SubmitControlledEvidence(ctx context.Context, c ControlledSubmission) error {
	r, seal, v := c.Request, c.Seal, c.Verification
	hash, err := r.Hash()
	if err != nil || !canonicalUUID(c.EvidenceID) || !canonicalUUID(c.ProducerTaskID) || c.LeaseEpoch < 1 || r.RunVersion != 3 {
		return ErrDeliveryDenied
	}
	var profile, harness, image string
	var minimum, maxAge int
	var now time.Time
	if i.db.QueryRowContext(ctx, `SELECT profile_sha256,harness_sha256,image_sha256,minimum_tests,maximum_age_seconds,clock_timestamp() FROM orchestration.delivery_verification_profiles WHERE policy_id=$1 AND policy_epoch=$2`, r.PolicyID, r.PolicyEpoch).Scan(&profile, &harness, &image, &minimum, &maxAge, &now) != nil {
		return ErrDeliveryDenied
	}
	fresh := func(issued, expires time.Time) bool {
		return !issued.IsZero() && !issued.After(now) && expires.After(now) && expires.After(issued) && now.Sub(issued) <= time.Duration(maxAge)*time.Second && expires.Sub(issued) <= time.Hour
	}
	if seal.ReceiptID != r.SealReceiptID || seal.RunID != r.RunID || seal.AttemptID != r.AttemptID || seal.BaseSHA != r.BaseSHA || seal.CommitSHA != r.CandidateCommitSHA || seal.TreeSHA != r.CandidateTreeSHA || seal.ManifestSHA256 != r.ManifestSHA256 || seal.WriterStoppedAt.IsZero() || seal.WriterStoppedAt.After(seal.SealedAt) || !fresh(seal.SealedAt, seal.ExpiresAt) || v.ReceiptID != r.VerificationReceiptID || v.RunID != r.RunID || v.AttemptID != r.AttemptID || v.SealReceiptID != seal.ReceiptID || v.ManifestSHA256 != r.ManifestSHA256 || r.VerifierProfileSHA256 != profile || v.ProfileSHA256 != profile || v.HarnessSHA256 != harness || v.ImageSHA256 != image || !isLowerHex(v.LogsSHA256, 64) || v.Discovered < minimum || v.Executed != v.Discovered || v.Skipped != 0 || v.Failed != 0 || v.ExitCode != 0 || v.TimedOut || v.IssuedAt.Before(seal.SealedAt) || !fresh(v.IssuedAt, v.ExpiresAt) {
		return ErrDeliveryDenied
	}
	expires := seal.ExpiresAt
	if v.ExpiresAt.Before(expires) {
		expires = v.ExpiresAt
	}
	submissionHash, err := privateDigest(c)
	if err != nil {
		return ErrDeliveryDenied
	}
	body, _ := json.Marshal(r)
	sealJSON, _ := json.Marshal(seal)
	verificationJSON, _ := json.Marshal(v)
	_, err = i.db.ExecContext(ctx, `INSERT INTO orchestration.controlled_candidate_evidence(evidence_id,run_id,producer_attempt_id,producer_task_id,claimant_id,lease_epoch,submission_hash,request_hash,request_json,seal_json,verification_json,expires_at) VALUES($1::uuid,$2::uuid,$3::uuid,$4::uuid,$5,$6,$7,$8,$9::jsonb,$10::jsonb,$11::jsonb,$12) ON CONFLICT DO NOTHING`, c.EvidenceID, r.RunID, r.AttemptID, c.ProducerTaskID, ControllerClaimant, c.LeaseEpoch, submissionHash, hash, string(body), string(sealJSON), string(verificationJSON), expires)
	if err != nil {
		return ErrDeliveryDenied
	}
	var matches bool
	if i.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$1::uuid AND run_id=$2::uuid AND request_hash=$3 AND submission_hash=$4 AND revoked_at IS NULL AND expires_at>clock_timestamp())`, c.EvidenceID, r.RunID, hash, submissionHash).Scan(&matches) != nil || !matches {
		return ErrDeliveryDenied
	}
	return nil
}
func (s *Store) AdvanceControlledReview(ctx context.Context, id string) (ReviewReceipt, error) {
	if !canonicalUUID(id) {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	defer tx.Rollback()
	var run string
	if tx.QueryRowContext(ctx, `SELECT run_id::text FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$1::uuid`, id).Scan(&run) != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	var state, trigger, project, branch, base, head, policy string
	var version, epoch int64
	if tx.QueryRowContext(ctx, `SELECT state,trigger_kind,run_version,gitlab_project_id,target_branch,repository_base_sha,observed_target_head_sha,policy_id,policy_epoch FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, run).Scan(&state, &trigger, &version, &project, &branch, &base, &head, &policy, &epoch) != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	// Historical receipt replay is not fresh delivery authority.
	var original ReviewReceipt
	original.EvidenceID = id
	err = tx.QueryRowContext(ctx, `SELECT run_id::text,review_attempt_id::text,review_task_id::text,request_hash FROM orchestration.controlled_review_receipts WHERE evidence_id=$1::uuid`, id).Scan(&original.RunID, &original.ReviewAttemptID, &original.ReviewTaskID, &original.RequestHash)
	if err == nil {
		return original, nil
	}
	if err != sql.ErrNoRows {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	policyExpiry, err := lockDeliveryPolicy(ctx, tx, policy, epoch)
	if err != nil {
		return ReviewReceipt{}, err
	}
	var body []byte
	var hash, producer, task, claimant string
	var lease int64
	var expires time.Time
	var revoked sql.NullTime
	if tx.QueryRowContext(ctx, `SELECT request_json,request_hash,producer_attempt_id::text,producer_task_id::text,claimant_id,lease_epoch,expires_at,revoked_at FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$1::uuid FOR SHARE`, id).Scan(&body, &hash, &producer, &task, &claimant, &lease, &expires, &revoked) != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	r, err := decodeDeliveryRequest(body)
	if err != nil {
		return ReviewReceipt{}, err
	}
	actual, _ := r.Hash()
	if trigger != "CONTROLLER_PROBE" || state != "IMPLEMENTING" || version != 1 || r.RunVersion != 3 || r.RunID != run || r.AttemptID != producer || actual != hash || project != "87786140" || branch != "main" || base != r.BaseSHA || head != r.ExpectedTargetSHA || policy != r.PolicyID || epoch != r.PolicyEpoch || revoked.Valid || claimant != ControllerClaimant {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	var locked string
	if tx.QueryRowContext(ctx, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, task, run).Scan(&locked) != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	var now time.Time
	if tx.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&now) != nil || !now.Before(expires) || !now.Before(policyExpiry) {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	result, err := tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status='COMPLETED',updated_at=clock_timestamp() WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid AND stage='IMPLEMENTING' AND status='LEASED' AND claimant_id=$4 AND lease_epoch=$5 AND lease_expires_at>clock_timestamp()`, task, run, producer, claimant, lease)
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	if n, _ := result.RowsAffected(); n != 1 {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	result, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='SUCCEEDED',completed_at=clock_timestamp() WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND stage='IMPLEMENTING' AND state='RUNNING'`, producer, run)
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	if n, _ := result.RowsAffected(); n != 1 {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	attempt, err := freshUUID()
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	reviewTask, err := freshUUID()
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	transition, err := freshUUID()
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	message, err := freshUUID()
	if err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	for _, change := range []struct {
		query string
		args  []any
	}{
		{`UPDATE orchestration.workflow_runs SET state='REVIEWING',run_version=2,updated_at=clock_timestamp() WHERE run_id=$1::uuid`, []any{run}},
		{`INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no,state) VALUES($1::uuid,$2::uuid,'REVIEWING',1,'RUNNING')`, []any{attempt, run}},
		{`INSERT INTO orchestration.task_deliveries(task_id,run_id,attempt_id,stage,status,claimant_id,lease_epoch,lease_expires_at) VALUES($1::uuid,$2::uuid,$3::uuid,'REVIEWING','LEASED',$4,1,LEAST($5::timestamptz,clock_timestamp()+interval '120 seconds'))`, []any{reviewTask, run, attempt, ControllerClaimant, expires}},
		{`INSERT INTO orchestration.workflow_transitions(transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref) VALUES($1::uuid,$2::uuid,$3::uuid,'IMPLEMENTING','REVIEWING',1,$4)`, []any{transition, run, producer, "controlled-evidence:" + id}},
		{`INSERT INTO orchestration.controlled_review_receipts(evidence_id,run_id,request_hash,review_attempt_id,review_task_id,review_lease_epoch) VALUES($1::uuid,$2::uuid,$3,$4::uuid,$5::uuid,1)`, []any{id, run, hash, attempt, reviewTask}},
	} {
		if _, err = tx.ExecContext(ctx, change.query, change.args...); err != nil {
			return ReviewReceipt{}, ErrDeliveryDenied
		}
	}
	payload, _ := json.Marshal(struct {
		EvidenceID  string `json:"evidenceId"`
		RequestHash string `json:"requestHash"`
	}{id, hash})
	digest := sha256.Sum256(payload)
	if _, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload) VALUES($1::uuid,$2::uuid,'CONTROLLED_REVIEW_READY',$3,$4::jsonb)`, message, run, hex.EncodeToString(digest[:]), string(payload)); err != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	if tx.Commit() != nil {
		return ReviewReceipt{}, ErrDeliveryDenied
	}
	return ReviewReceipt{id, run, attempt, reviewTask, hash}, nil
}

// IssueDeliveryReview independently loads Go's committed review context. A caller
// cannot choose a review task, set a successful state or manufacture a lease.
func (i *ReviewIssuer) IssueDeliveryReview(ctx context.Context, id string) error {
	if !canonicalUUID(id) {
		return ErrDeliveryDenied
	}
	var run, producer, attempt, task, hash, claimant, state string
	var body []byte
	var epoch, version int64
	var expires, leaseExpires time.Time
	var revoked sql.NullTime
	err := i.db.QueryRowContext(ctx, `SELECT run_id::text,producer_attempt_id::text,review_attempt_id::text,review_task_id::text,request_hash,request_json,review_lease_epoch,expires_at,revoked_at,state,run_version,claimant_id,lease_expires_at FROM orchestration.controlled_review_context WHERE evidence_id=$1::uuid`, id).Scan(&run, &producer, &attempt, &task, &hash, &body, &epoch, &expires, &revoked, &state, &version, &claimant, &leaseExpires)
	if err != nil || revoked.Valid || claimant != ControllerClaimant {
		return ErrDeliveryDenied
	}
	r, err := decodeDeliveryRequest(body)
	if err != nil {
		return err
	}
	actual, _ := r.Hash()
	if actual != hash || r.RunID != run || r.AttemptID != producer {
		return ErrDeliveryDenied
	}
	// An exact historical review row can be replayed after later workflow progress.
	var prior bool
	if i.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.delivery_review_evidence WHERE evidence_id=$1::uuid AND run_id=$2::uuid AND request_hash=$3 AND revoked_at IS NULL AND expires_at>clock_timestamp())`, id, run, hash).Scan(&prior) != nil {
		return ErrDeliveryDenied
	}
	if prior {
		return nil
	}
	if state != "REVIEWING" || version != 2 {
		return ErrDeliveryDenied
	}
	_, err = i.db.ExecContext(ctx, `INSERT INTO orchestration.delivery_review_evidence(evidence_id,run_id,producer_attempt_id,review_attempt_id,task_id,claimant_id,lease_epoch,expected_run_version,policy_id,policy_epoch,request_hash,request_json,expires_at) SELECT $1::uuid,$2::uuid,$3::uuid,$4::uuid,$5::uuid,$6,$7,2,$8,$9,$10,$11::jsonb,$12 WHERE $12::timestamptz>clock_timestamp() AND $13::timestamptz>clock_timestamp() ON CONFLICT DO NOTHING`, id, run, producer, attempt, task, claimant, epoch, r.PolicyID, r.PolicyEpoch, hash, string(body), expires, leaseExpires)
	if err != nil {
		return ErrDeliveryDenied
	}
	if i.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.delivery_review_evidence WHERE evidence_id=$1::uuid AND request_hash=$2 AND revoked_at IS NULL AND expires_at>clock_timestamp())`, id, hash).Scan(&prior) != nil || !prior {
		return ErrDeliveryDenied
	}
	return nil
}
