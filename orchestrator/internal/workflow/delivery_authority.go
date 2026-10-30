package workflow

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"strconv"
	"time"
)

var ErrDeliveryDenied = errors.New("delivery authority unavailable or denied")

// DeliveryRequest is the immutable sandbox wire identity, never authority by itself.
// Keep this length-prefixed SHA-256 codec compatible with Java's existing request v1.
type DeliveryRequest struct {
	OperationID           string `json:"operationId"`
	RunID                 string `json:"runId"`
	AttemptID             string `json:"attemptId"`  // Producer, not review or delivery task attempt.
	RunVersion            int64  `json:"runVersion"` // Version AFTER REVIEWING -> DELIVERY_PENDING.
	OwnerGrantID          string `json:"ownerGrantId"`
	PolicyID              string `json:"policyId"`
	PolicyEpoch           int64  `json:"policyEpoch"`
	BaseSHA               string `json:"baseSha"`
	ExpectedTargetSHA     string `json:"expectedTargetSha"`
	CandidateCommitSHA    string `json:"candidateCommitSha"`
	CandidateTreeSHA      string `json:"candidateTreeSha"`
	ManifestSHA256        string `json:"manifestSha256"`
	SealReceiptID         string `json:"sealReceiptId"`
	VerificationReceiptID string `json:"verificationReceiptId"`
	VerifierProfileSHA256 string `json:"verifierProfileSha256"`
	SafeCIProfileSHA256   string `json:"safeCiProfileSha256"`
}

func (r DeliveryRequest) Hash() (string, error) {
	for _, id := range []string{r.OperationID, r.RunID, r.AttemptID, r.OwnerGrantID, r.SealReceiptID, r.VerificationReceiptID} {
		if !canonicalUUID(id) {
			return "", ErrDeliveryDenied
		}
	}
	if r.RunVersion < 2 || r.PolicyEpoch < 1 || len(r.PolicyID) < 1 || len(r.PolicyID) > 101 || r.SealReceiptID == r.VerificationReceiptID {
		return "", ErrDeliveryDenied
	}
	for i, c := range r.PolicyID {
		if !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || i > 0 && (c == '.' || c == '_' || c == ':' || c == '-')) {
			return "", ErrDeliveryDenied
		}
	}
	for _, sha := range []string{r.BaseSHA, r.ExpectedTargetSHA, r.CandidateCommitSHA, r.CandidateTreeSHA} {
		if !isLowerHex(sha, 40) {
			return "", ErrDeliveryDenied
		}
	}
	for _, sha := range []string{r.ManifestSHA256, r.VerifierProfileSHA256, r.SafeCIProfileSHA256} {
		if !isLowerHex(sha, 64) {
			return "", ErrDeliveryDenied
		}
	}
	if r.BaseSHA != r.ExpectedTargetSHA {
		return "", ErrDeliveryDenied
	}
	fields := []string{"asep-vn-t14-test-project-request-v1", r.OperationID, r.RunID, r.AttemptID, strconv.FormatInt(r.RunVersion, 10), r.OwnerGrantID, r.PolicyID, strconv.FormatInt(r.PolicyEpoch, 10), "87786140", r.BaseSHA, "test/vn-t14/" + r.OperationID, "main", r.ExpectedTargetSHA, r.CandidateCommitSHA, r.CandidateTreeSHA, r.ManifestSHA256, r.SealReceiptID, r.VerificationReceiptID, r.VerifierProfileSHA256, r.SafeCIProfileSHA256}
	hash := sha256.New()
	for _, field := range fields {
		_ = binary.Write(hash, binary.BigEndian, uint32(len([]byte(field))))
		_, _ = hash.Write([]byte(field))
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
func decodeDeliveryRequest(body []byte) (DeliveryRequest, error) {
	var r DeliveryRequest
	if len(body) > 8192 {
		return r, ErrDeliveryDenied
	}
	d := json.NewDecoder(bytes.NewReader(body))
	d.DisallowUnknownFields()
	if d.Decode(&r) != nil || d.Decode(new(any)) != io.EOF {
		return r, ErrDeliveryDenied
	}
	if _, err := r.Hash(); err != nil {
		return r, err
	}
	return r, nil
}

type DeliveryAuthority struct {
	OperationID            string    `json:"operationId"`
	AuthorizedRequestHash  string    `json:"authorizedRequestHash"`
	RunID                  string    `json:"runId"`
	AttemptID              string    `json:"attemptId"`
	RunVersion             int64     `json:"runVersion"`
	State                  string    `json:"state"`
	PolicyID               string    `json:"policyId"`
	PolicyEpoch            int64     `json:"policyEpoch"`
	ProjectID              int64     `json:"projectId"`
	TargetBranch           string    `json:"targetBranch"`
	BaseSHA                string    `json:"baseSha"`
	TargetHeadSHA          string    `json:"targetHeadSha"`
	DeliveryPolicyApproved bool      `json:"deliveryPolicyApproved"`
	ObservedAt             time.Time `json:"observedAt"`
	ValidUntil             time.Time `json:"validUntil"`
}
type deliveryReview struct {
	request                                             DeliveryRequest
	hash, run, producer, review, task, claimant, policy string
	epoch, version, lease                               int64
	expires                                             time.Time
	revoked                                             sql.NullTime
}

func readDeliveryReview(ctx context.Context, tx *sql.Tx, id string) (deliveryReview, error) {
	var e deliveryReview
	var body []byte
	err := tx.QueryRowContext(ctx, `SELECT run_id::text,producer_attempt_id::text,review_attempt_id::text,task_id::text,claimant_id,lease_epoch,expected_run_version,policy_id,policy_epoch,request_hash,request_json,expires_at,revoked_at
        FROM orchestration.delivery_review_evidence WHERE evidence_id=$1::uuid FOR SHARE`, id).Scan(&e.run, &e.producer, &e.review, &e.task, &e.claimant, &e.lease, &e.version, &e.policy, &e.epoch, &e.hash, &body, &e.expires, &e.revoked)
	if err != nil {
		return e, ErrDeliveryDenied
	}
	e.request, err = decodeDeliveryRequest(body)
	if err != nil {
		return e, err
	}
	hash, err := e.request.Hash()
	if err != nil || hash != e.hash || e.request.RunID != e.run || e.request.AttemptID != e.producer || e.request.RunVersion != e.version+1 || e.request.PolicyID != e.policy || e.request.PolicyEpoch != e.epoch {
		return e, ErrDeliveryDenied
	}
	return e, nil
}

// lockDeliveryPolicy serializes current-policy edits with acceptance. Fresh DB time
// is checked after all potentially waiting row locks, never from transaction start.
func lockDeliveryPolicy(ctx context.Context, tx *sql.Tx, policy string, epoch int64) (time.Time, error) {
	var actual int64
	var enabled bool
	var expires time.Time
	err := tx.QueryRowContext(ctx, `SELECT policy_epoch,enabled,expires_at FROM orchestration.delivery_policies WHERE policy_id=$1 FOR SHARE`, policy).Scan(&actual, &enabled, &expires)
	if err != nil || !enabled || actual != epoch {
		return time.Time{}, ErrDeliveryDenied
	}
	return expires, nil
}

// AuthorizeDelivery accepts only an independently issued review ID. No caller boolean,
// synthetic prefix or request payload can manufacture the protected review/policy rows.
// Upstream real REVIEWING entry and protected evidence issuance must exist before use.
func (s *Store) AuthorizeDelivery(ctx context.Context, evidenceID string) (DeliveryAuthority, error) {
	if !canonicalUUID(evidenceID) {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	defer tx.Rollback()
	// Discover stable run identity, then use the common run -> policy -> evidence -> task order.
	var run string
	if tx.QueryRowContext(ctx, `SELECT run_id::text FROM orchestration.delivery_review_evidence WHERE evidence_id=$1::uuid`, evidenceID).Scan(&run) != nil {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	var state, project, branch, base, head, policy string
	var version, epoch int64
	if tx.QueryRowContext(ctx, `SELECT state,run_version,gitlab_project_id,target_branch,repository_base_sha,observed_target_head_sha,policy_id,policy_epoch FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, run).Scan(&state, &version, &project, &branch, &base, &head, &policy, &epoch) != nil {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	policyExpiry, err := lockDeliveryPolicy(ctx, tx, policy, epoch)
	if err != nil {
		return DeliveryAuthority{}, err
	}
	e, err := readDeliveryReview(ctx, tx, evidenceID)
	if err != nil {
		return DeliveryAuthority{}, err
	}
	r := e.request
	if project != "87786140" || branch != "main" || base != r.BaseSHA || head != r.ExpectedTargetSHA || policy != e.policy || epoch != e.epoch || e.revoked.Valid {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	if state == "DELIVERY_PENDING" && version == r.RunVersion {
		var match bool
		err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.delivery_authorizations WHERE operation_id=$1::uuid AND run_id=$2::uuid AND request_hash=$3 AND evidence_id=$4::uuid)`, r.OperationID, run, e.hash, evidenceID).Scan(&match)
		if err != nil || !match {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
	} else {
		if state != "REVIEWING" || version != e.version {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		var task string
		if tx.QueryRowContext(ctx, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, e.task, run).Scan(&task) != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		var now time.Time
		if tx.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&now) != nil || !now.Before(e.expires) || !now.Before(policyExpiry) {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		result, err := tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status='COMPLETED',updated_at=clock_timestamp() WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid AND stage='REVIEWING' AND status='LEASED' AND claimant_id=$4 AND lease_epoch=$5 AND lease_expires_at>clock_timestamp()`, e.task, run, e.review, e.claimant, e.lease)
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		if n, _ := result.RowsAffected(); n != 1 {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		result, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='SUCCEEDED',completed_at=clock_timestamp() WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND stage='REVIEWING' AND state='RUNNING' AND EXISTS(SELECT 1 FROM orchestration.stage_attempts WHERE attempt_id=$3::uuid AND run_id=$2::uuid AND stage='IMPLEMENTING' AND state='SUCCEEDED')`, e.review, run, e.producer)
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		if n, _ := result.RowsAffected(); n != 1 {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		attempt, err := freshUUID()
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		transition, err := freshUUID()
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		message, err := freshUUID()
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		_, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state='DELIVERY_PENDING',run_version=run_version+1,updated_at=clock_timestamp() WHERE run_id=$1::uuid`, run)
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no) VALUES($1::uuid,$2::uuid,'DELIVERY_PENDING',1)`, attempt, run)
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_transitions(transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref) VALUES($1::uuid,$2::uuid,$3::uuid,'REVIEWING','DELIVERY_PENDING',$4,$5)`, transition, run, e.review, version, "delivery-review:"+evidenceID)
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		payload, _ := json.Marshal(struct {
			OperationID string `json:"operationId"`
			RequestHash string `json:"requestHash"`
		}{r.OperationID, e.hash})
		digest := sha256.Sum256(payload)
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload) VALUES($1::uuid,$2::uuid,'DELIVERY_AUTHORIZED',$3,$4::jsonb)`, message, run, hex.EncodeToString(digest[:]), string(payload))
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.delivery_authorizations(operation_id,run_id,request_hash,evidence_id,delivery_attempt_id,transition_id,outbox_message_id) VALUES($1::uuid,$2::uuid,$3,$4::uuid,$5::uuid,$6::uuid,$7::uuid)`, r.OperationID, run, e.hash, evidenceID, attempt, transition, message)
		if err != nil {
			return DeliveryAuthority{}, ErrDeliveryDenied
		}
	}
	// Re-evaluate expirations on replay too. Commit failure grants no authority;
	// current read by the exact identity can reconcile a lost response later.
	var now time.Time
	if tx.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&now) != nil || !now.Before(e.expires) || !now.Before(policyExpiry) {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	if tx.Commit() != nil {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	return s.CurrentDeliveryAuthority(ctx, r.OperationID, run, r.AttemptID)
}

// CurrentDeliveryAuthority recomputes eligibility in one database snapshot. Its
// observation starts BEFORE database/lock waits, so latency cannot refresh old facts.
func (s *Store) CurrentDeliveryAuthority(ctx context.Context, operation, run, producer string) (DeliveryAuthority, error) {
	started := time.Now().UTC()
	if !canonicalUUID(operation) || !canonicalUUID(run) || !canonicalUUID(producer) {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	var body []byte
	var result DeliveryAuthority
	err := s.db.QueryRowContext(ctx, `SELECT e.request_json,a.request_hash,r.run_version,r.state,r.policy_id,r.policy_epoch,r.gitlab_project_id::bigint,r.target_branch,r.repository_base_sha,r.observed_target_head_sha,LEAST(p.expires_at,e.expires_at)
        FROM orchestration.delivery_authorizations a
        JOIN orchestration.delivery_review_evidence e ON e.evidence_id=a.evidence_id
        JOIN orchestration.workflow_runs r ON r.run_id=a.run_id
        JOIN orchestration.delivery_policies p ON p.policy_id=r.policy_id
        LEFT JOIN orchestration.controlled_candidate_evidence c ON c.evidence_id=e.evidence_id
        JOIN orchestration.stage_attempts d ON d.attempt_id=a.delivery_attempt_id AND d.run_id=r.run_id
        WHERE a.operation_id=$1::uuid AND a.run_id=$2::uuid AND e.producer_attempt_id=$3::uuid
          AND e.run_id=a.run_id AND e.request_hash=a.request_hash
          AND r.state='DELIVERY_PENDING' AND r.run_version=e.expected_run_version+1
          AND r.policy_id=e.policy_id AND r.policy_epoch=e.policy_epoch AND p.policy_epoch=r.policy_epoch
          AND p.enabled AND p.expires_at>clock_timestamp() AND e.expires_at>clock_timestamp() AND e.revoked_at IS NULL
          AND (c.evidence_id IS NULL OR c.revoked_at IS NULL AND c.expires_at>clock_timestamp())
          AND d.stage='DELIVERY_PENDING' AND d.state='PENDING'
          AND r.gitlab_project_id='87786140' AND r.target_branch='main'`, operation, run, producer).Scan(&body, &result.AuthorizedRequestHash, &result.RunVersion, &result.State, &result.PolicyID, &result.PolicyEpoch, &result.ProjectID, &result.TargetBranch, &result.BaseSHA, &result.TargetHeadSHA, &result.ValidUntil)
	if err != nil {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	request, err := decodeDeliveryRequest(body)
	if err != nil {
		return DeliveryAuthority{}, err
	}
	hash, _ := request.Hash()
	if hash != result.AuthorizedRequestHash || request.OperationID != operation || request.RunID != run || request.AttemptID != producer || request.RunVersion != result.RunVersion || request.PolicyID != result.PolicyID || request.PolicyEpoch != result.PolicyEpoch || request.BaseSHA != result.BaseSHA || request.ExpectedTargetSHA != result.TargetHeadSHA {
		return DeliveryAuthority{}, ErrDeliveryDenied
	}
	result.OperationID = operation
	result.RunID = run
	result.AttemptID = producer
	result.DeliveryPolicyApproved = true
	result.ObservedAt = started
	return result, nil
}

// CancelDelivery invalidates pending authority under the same run lock. It records
// stop intent only; cleanup and any remote effects still require reconciliation.
func (s *Store) CancelDelivery(ctx context.Context, run string, expectedVersion int64) error {
	if !canonicalUUID(run) || expectedVersion < 1 {
		return ErrDeliveryDenied
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrDeliveryDenied
	}
	defer tx.Rollback()
	var state, trigger string
	var version int64
	if tx.QueryRowContext(ctx, `SELECT state,trigger_kind,run_version FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, run).Scan(&state, &trigger, &version) != nil {
		return ErrDeliveryDenied
	}
	// Stable per-run/version stop intent. Historical replay reports only its original
	// acceptance, never a fresh delivery capability or a second fencing increment.
	hash := deliveryCancelHash(run, expectedVersion)
	id := hash[:8] + "-" + hash[8:12] + "-" + hash[12:16] + "-" + hash[16:20] + "-" + hash[20:32]
	var accepted bool
	if tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.workflow_transitions WHERE control_operation_id=$1::uuid AND run_id=$2::uuid AND prior_run_version=$3 AND control_kind='DELIVERY_CANCEL' AND control_payload_sha256=$4)`, id, run, expectedVersion, hash).Scan(&accepted) != nil {
		return ErrDeliveryDenied
	}
	if accepted {
		return nil
	}
	if version != expectedVersion || (state != "REVIEWING" && state != "DELIVERY_PENDING" && !(state == "IMPLEMENTING" && trigger == "CONTROLLER_PROBE")) {
		return ErrDeliveryDenied
	}
	_, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state='CANCEL_REQUESTED',run_version=run_version+1,updated_at=clock_timestamp() WHERE run_id=$1::uuid`, run)
	if err != nil {
		return ErrDeliveryDenied
	}
	_, err = tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status='CANCELLED',lease_epoch=lease_epoch+1,updated_at=clock_timestamp() WHERE run_id=$1::uuid AND status IN ('QUEUED','LEASED')`, run)
	if err != nil {
		return ErrDeliveryDenied
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_transitions(transition_id,run_id,from_state,to_state,prior_run_version,evidence_ref,control_operation_id,control_kind,control_digest_version,control_payload_sha256) VALUES($1::uuid,$2::uuid,$3,'CANCEL_REQUESTED',$4,'trusted-delivery-cancellation',$1::uuid,'DELIVERY_CANCEL',1,$5)`, id, run, state, version, deliveryCancelHash(run, version))
	if err != nil {
		return ErrDeliveryDenied
	}
	msg, err := freshUUID()
	if err != nil {
		return ErrDeliveryDenied
	}
	body, _ := json.Marshal(struct {
		RunID   string `json:"runId"`
		Version int64  `json:"runVersion"`
	}{run, version + 1})
	digest := sha256.Sum256(body)
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload) VALUES($1::uuid,$2::uuid,'DELIVERY_CANCEL_REQUESTED',$3,$4::jsonb)`, msg, run, hex.EncodeToString(digest[:]), string(body))
	if err != nil {
		return ErrDeliveryDenied
	}
	if tx.Commit() != nil {
		return ErrDeliveryDenied
	}
	return nil
}

func deliveryCancelHash(run string, version int64) string {
	digest := sha256.Sum256([]byte("asep-delivery-cancel-v1:" + run + ":" + strconv.FormatInt(version, 10)))
	return hex.EncodeToString(digest[:])
}

// ValidateDeliveryRuntime enforces the protected listener's database identity.
// Development Open remains compatible; it does not grant this deployment capability.
func (s *Store) ValidateDeliveryRuntime(ctx context.Context) error {
	var allowed bool
	err := s.db.QueryRowContext(ctx, `SELECT session_user=current_user AND session_user='asep_orch_runtime' AND NOT(rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls) AND NOT pg_has_role(session_user,'asep_orch_authority','USAGE') AND NOT has_schema_privilege(session_user,'orchestration','CREATE') AND NOT has_table_privilege(session_user,'orchestration.delivery_review_evidence','INSERT') AND NOT has_table_privilege(session_user,'orchestration.controlled_candidate_evidence','INSERT') FROM pg_roles WHERE rolname=session_user`).Scan(&allowed)
	if err != nil || !allowed {
		return ErrDeliveryDenied
	}
	return nil
}
