//go:build integration && deliveryauthority

package workflow

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func controlledFixture(t *testing.T) (*Store, *ReviewIssuer, ControlledSubmission) {
	t.Helper()
	s := storeForTest(t)
	ctx := testContext(t)
	issuer := &ReviewIssuer{deliveryAuthorityDatabase(t, "asep_orch_authority", "ASEP_DELIVERY_AUTHORITY_TEST_PASSWORD")}
	id := func() string {
		v, err := freshUUID()
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	operation := id()
	policy := "controlled-" + operation
	_, err := issuer.db.ExecContext(ctx, `INSERT INTO orchestration.delivery_policies(policy_id,policy_epoch,project_id,repository_path,target_branch,enabled,expires_at) VALUES($1,1,87786140,'qhung-13/asep-m01-sandbox','main',true,clock_timestamp()+interval '15 minutes')`, policy)
	if err != nil {
		t.Fatal(err)
	}
	_, err = issuer.db.ExecContext(ctx, `INSERT INTO orchestration.delivery_verification_profiles VALUES($1,1,$2,$3,$4,2,300)`, policy, strings.Repeat("a", 64), strings.Repeat("b", 64), strings.Repeat("c", 64))
	if err != nil {
		t.Fatal(err)
	}
	start := ControlledStart{operation, policy, 1, strings.Repeat("a", 40)}
	admitted, err := s.AdmitControlledProbe(ctx, start)
	if err != nil {
		t.Fatal("controlled admission failed", err)
	}
	repeated, err := s.AdmitControlledProbe(ctx, start)
	if err != nil || admitted != repeated {
		t.Fatal("controlled admission not idempotent")
	}
	lease, err := s.ClaimControlledProbe(ctx, admitted.RunID)
	if err != nil {
		t.Fatal("controlled claim failed", err)
	}
	r := DeliveryRequest{operation, admitted.RunID, admitted.ProducerAttemptID, 3, id(), policy, 1, start.BaseSHA, start.BaseSHA, strings.Repeat("b", 40), strings.Repeat("c", 40), strings.Repeat("d", 64), id(), id(), strings.Repeat("a", 64), strings.Repeat("e", 64)}
	now := time.Now().UTC().Add(-time.Second)
	seal := CandidateSeal{r.SealReceiptID, r.RunID, r.AttemptID, r.BaseSHA, r.CandidateCommitSHA, r.CandidateTreeSHA, r.ManifestSHA256, now, now, now.Add(5 * time.Minute)}
	verification := CandidateVerification{r.VerificationReceiptID, r.RunID, r.AttemptID, r.SealReceiptID, r.ManifestSHA256, r.VerifierProfileSHA256, strings.Repeat("b", 64), strings.Repeat("c", 64), strings.Repeat("f", 64), 2, 2, 0, 0, 0, false, now, now.Add(5 * time.Minute)}
	// Struct fixtures test issuer validation only. The cross-language end-to-end
	// fixture below this slice supplies actual controller/isolated-verifier output.
	return s, issuer, ControlledSubmission{operation, lease.TaskID, lease.LeaseEpoch, r, seal, verification}
}
func TestControlledDeliveryProgressesThroughGoContractsWithoutStateSeeding(t *testing.T) {
	s, issuer, c := controlledFixture(t)
	ctx := testContext(t)
	if _, err := s.AdvanceControlledReview(ctx, c.EvidenceID); err == nil {
		t.Fatal("unissued controller evidence advanced workflow")
	}
	if err := issuer.SubmitControlledEvidence(ctx, c); err != nil {
		t.Fatal(err)
	}
	receipt, err := s.AdvanceControlledReview(ctx, c.EvidenceID)
	if err != nil {
		t.Fatal(err)
	}
	again, err := s.AdvanceControlledReview(ctx, c.EvidenceID)
	if err != nil || again != receipt {
		t.Fatal("review replay changed identity")
	}
	if err := issuer.IssueDeliveryReview(ctx, c.EvidenceID); err != nil {
		t.Fatal(err)
	}
	authority, err := s.AuthorizeDelivery(ctx, c.EvidenceID)
	if err != nil || authority.RunVersion != 3 || authority.AttemptID != c.Request.AttemptID {
		t.Fatal("authorized controller chain failed", err)
	}
	var falseUpstream int
	if s.db.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.stage_attempts WHERE run_id=$1::uuid AND stage IN ('INVESTIGATING','PLANNING')`, c.Request.RunID).Scan(&falseUpstream) != nil || falseUpstream != 0 {
		t.Fatal("controlled entry invented missing upstream stages")
	}
}
func TestControlledDeliveryRejectsInvalidIndependentProof(t *testing.T) {
	for _, fault := range []string{"run", "candidate", "profile", "tests", "skip", "image", "expired", "not-stopped"} {
		t.Run(fault, func(t *testing.T) {
			_, issuer, c := controlledFixture(t)
			switch fault {
			case "run":
				c.Seal.RunID, _ = freshUUID()
			case "candidate":
				c.Seal.CommitSHA = strings.Repeat("9", 40)
			case "profile":
				c.Verification.ProfileSHA256 = strings.Repeat("9", 64)
			case "tests":
				c.Verification.Discovered = 0
				c.Verification.Executed = 0
			case "skip":
				c.Verification.Skipped = 1
			case "image":
				c.Verification.ImageSHA256 = strings.Repeat("9", 64)
			case "expired":
				c.Seal.ExpiresAt = time.Now().Add(-time.Second)
			case "not-stopped":
				c.Seal.WriterStoppedAt = c.Seal.SealedAt.Add(time.Second)
			}
			if err := issuer.SubmitControlledEvidence(testContext(t), c); err == nil {
				t.Fatal("invalid proof admitted")
			}
		})
	}
}
func TestControlledDeliveryRejectsChangedReplayAndRevokedEvidence(t *testing.T) {
	s, issuer, c := controlledFixture(t)
	ctx := testContext(t)
	if issuer.SubmitControlledEvidence(ctx, c) != nil {
		t.Fatal("submission failed")
	}
	changed := c
	changed.Verification.LogsSHA256 = strings.Repeat("0", 64)
	if issuer.SubmitControlledEvidence(ctx, changed) == nil {
		t.Fatal("same evidence ID accepted changed proof")
	}
	if _, err := issuer.db.ExecContext(ctx, `UPDATE orchestration.controlled_candidate_evidence SET revoked_at=clock_timestamp() WHERE evidence_id=$1::uuid`, c.EvidenceID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AdvanceControlledReview(ctx, c.EvidenceID); err == nil {
		t.Fatal("revoked evidence advanced workflow")
	}
}
func TestControlledDeliveryRuntimeCannotIssueProofOrRewriteHistory(t *testing.T) {
	s, issuer, c := controlledFixture(t)
	ctx := testContext(t)
	if issuer.SubmitControlledEvidence(ctx, c) != nil {
		t.Fatal("submission failed")
	}
	for _, q := range []string{`INSERT INTO orchestration.delivery_verification_profiles(policy_id) VALUES($1)`, `UPDATE orchestration.controlled_candidate_evidence SET evidence_id=evidence_id WHERE run_id=$1::uuid`, `UPDATE orchestration.controlled_review_receipts SET request_hash=request_hash WHERE run_id=$1::uuid`} {
		if _, err := s.db.ExecContext(ctx, q, c.Request.RunID); err == nil {
			t.Fatal("runtime acquired proof/history mutation")
		}
	}
	raw, _ := json.Marshal(c.Request)
	if _, err := issuer.db.ExecContext(ctx, `UPDATE orchestration.controlled_candidate_evidence SET request_json=$2::jsonb WHERE evidence_id=$1::uuid`, c.EvidenceID, string(raw)); err == nil {
		t.Fatal("issuer rewrote immutable proof")
	}
}

func TestControlledDeliveryRevocationInvalidatesDerivedAuthority(t *testing.T) {
	s, issuer, c := controlledFixture(t)
	ctx := testContext(t)
	if issuer.SubmitControlledEvidence(ctx, c) != nil {
		t.Fatal("submission failed")
	}
	if _, err := s.AdvanceControlledReview(ctx, c.EvidenceID); err != nil {
		t.Fatal(err)
	}
	if issuer.IssueDeliveryReview(ctx, c.EvidenceID) != nil {
		t.Fatal("review issue failed")
	}
	if _, err := s.AuthorizeDelivery(ctx, c.EvidenceID); err != nil {
		t.Fatal(err)
	}
	if _, err := issuer.db.ExecContext(ctx, `UPDATE orchestration.controlled_candidate_evidence SET revoked_at=clock_timestamp() WHERE evidence_id=$1::uuid`, c.EvidenceID); err != nil {
		t.Fatal("revocation failed")
	}
	if _, err := s.CurrentDeliveryAuthority(ctx, c.Request.OperationID, c.Request.RunID, c.Request.AttemptID); err == nil {
		t.Fatal("revoked controller proof retained derived authority")
	}
}
