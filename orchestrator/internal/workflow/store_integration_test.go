//go:build integration

package workflow

import (
	"context"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

func testContext(t *testing.T) context.Context { return testsupport.Context(t) }

func storeForTest(t *testing.T) *Store {
	t.Helper()
	url := testsupport.DatabaseURL(t)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	s, err := Open(ctx, url)
	if err != nil {
		t.Fatal("verified disposable test store unavailable")
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func admissionForTest(t *testing.T, extra string) Admission {
	t.Helper()
	prefix := os.Getenv("ASEP_VN_T10_TEST_PREFIX")
	if !strings.HasPrefix(prefix, "synthetic:vn-t10-it-") {
		t.Fatal("test prefix is not scoped to synthetic fixtures")
	}
	id, err := freshUUID()
	if err != nil {
		t.Fatal(err)
	}
	command, err := freshUUID()
	if err != nil {
		t.Fatal(err)
	}
	return Admission{
		CommandID:  command,
		SubjectKey: prefix + ":" + extra + ":" + id,
		ActorID:    "synthetic-owner", JiraSiteID: "synthetic-site",
		JiraProjectID: "synthetic-project", JiraIssueID: "synthetic-ticket-" + id,
		GitLabProjectID: "synthetic-gitlab", RepositoryBaseSHA: strings.Repeat("0", 40),
		TargetBranch: "main", ObservedTargetHeadSHA: strings.Repeat("1", 40),
		SourceFingerprintSHA256: strings.Repeat("b", 64), PolicyID: "synthetic-policy",
		PolicyEpoch: 1, BudgetLimit: 3,
	}
}

func admitForTest(t *testing.T, s *Store, extra string) (Admission, AdmissionReceipt) {
	t.Helper()
	c := admissionForTest(t, extra)
	r, err := s.Admit(testContext(t), c)
	if err != nil {
		t.Fatalf("admit: %v", err)
	}
	return c, r
}

func TestAdmissionReplayConflictAtomicBundle(t *testing.T) {
	s := storeForTest(t)
	ctx := testContext(t)
	cmd, r := admitForTest(t, s, "admission")
	if r.RunID == "" || r.TaskID == "" || r.AttemptID == "" || r.Replayed {
		t.Fatalf("invalid admission receipt: %+v", r)
	}
	replay, err := s.Admit(ctx, cmd)
	if err != nil || !replay.Replayed || replay.RunID != r.RunID || replay.TaskID != r.TaskID {
		t.Fatalf("idempotent replay failed: %+v err=%v", replay, err)
	}
	altered := cmd
	altered.PayloadSHA256 = strings.Repeat("c", 64)
	if _, err = s.Admit(ctx, altered); !errors.Is(err, ErrConflict) {
		t.Fatalf("forged asserted hash accepted: %v", err)
	}
	altered = cmd
	altered.ActorID = "synthetic-different-owner"
	if _, err = s.Admit(ctx, altered); !errors.Is(err, ErrConflict) {
		t.Fatalf("same command ID with changed actor accepted: %v", err)
	}
	originalDigest, err := cmd.canonicalPayloadSHA256()
	if err != nil {
		t.Fatal(err)
	}
	altered = cmd
	altered.PolicyEpoch++
	altered.PayloadSHA256 = originalDigest // attacker reuses original declared digest
	if _, err = s.Admit(ctx, altered); !errors.Is(err, ErrConflict) {
		t.Fatalf("same claimed hash with changed policy accepted: %v", err)
	}
	differentID := cmd
	differentID.CommandID, _ = freshUUID()
	if _, err = s.Admit(ctx, differentID); !errors.Is(err, ErrConflict) {
		t.Fatalf("same active subject accepted: %v", err)
	}
	var state string
	var version, budget int
	if err = s.db.QueryRowContext(ctx, `SELECT state,run_version,budget_used FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, r.RunID).Scan(&state, &version, &budget); err != nil {
		t.Fatal(err)
	}
	if state != "INVESTIGATING" || version != 1 || budget != 0 {
		t.Fatalf("wrong run state/version/budget: %s/%d/%d", state, version, budget)
	}
	tables := []string{"stage_attempts", "task_deliveries", "workflow_transitions", "workflow_outbox"}
	for _, table := range tables {
		// Table identifiers are a fixed source-coded allowlist, never user input.
		query := "SELECT count(*) FROM orchestration." + table + " WHERE run_id=$1::uuid"
		var n int
		if err = s.db.QueryRowContext(ctx, query, r.RunID).Scan(&n); err != nil || n != 1 {
			t.Fatalf("%s rows=%d err=%v", table, n, err)
		}
	}
}

func TestConcurrentClaimSingleOwner(t *testing.T) {
	s := storeForTest(t)
	_, r := admitForTest(t, s, "concurrency")
	const workers = 16
	type response struct {
		lease TaskLease
		err   error
	}
	responses := make(chan response, workers)
	start := make(chan struct{})
	var group sync.WaitGroup
	for i := 0; i < workers; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			<-start
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			defer cancel()
			lease, err := s.ClaimForRun(ctx, r.RunID, "synthetic-worker-"+string(rune('a'+i)), 15)
			responses <- response{lease, err}
		}(i)
	}
	close(start)
	group.Wait()
	close(responses)
	claimed := 0
	var winner TaskLease
	for result := range responses {
		if result.err == nil {
			claimed++
			winner = result.lease
			if result.lease.LeaseEpoch != 1 || result.lease.DeliveryCount != 1 {
				t.Errorf("unexpected initial lease: %+v", result.lease)
			}
		} else if !errors.Is(result.err, ErrNoTask) {
			t.Errorf("claim error: %v", result.err)
		}
	}
	if claimed != 1 {
		t.Fatalf("expected one winner, got %d", claimed)
	}
	z := snapshotFor(t, s, r)
	if z.firstTaskClaimant != winner.ClaimantID || z.firstTaskEpoch != winner.LeaseEpoch ||
		z.firstTaskDeliveries != winner.DeliveryCount || z.firstTaskDeliveries != 1 ||
		!z.firstTaskExpiry.Valid || !z.firstTaskExpiry.Time.Equal(winner.ExpiresAt) ||
		z.firstTaskStatus != "LEASED" || z.firstAttemptState != "RUNNING" ||
		z.firstAttemptNo != 1 || z.tasks != 1 || z.attempts != 1 || z.outstanding != 1 ||
		z.transitions != 1 || z.outbox != 1 || z.state != "INVESTIGATING" || z.version != 1 || z.used != 0 ||
		winner.TaskID != r.TaskID || winner.AttemptID != r.AttemptID {
		t.Fatalf("RF-03: persisted authority does not match the single winner: %+v", z)
	}
	t.Logf("RF-03 observed: winners=%d tasks=%d epoch=%d deliveries=%d semantic_attempt=%d version=%d budget=%d; persisted claimant matches winner", claimed, z.tasks, z.firstTaskEpoch, z.firstTaskDeliveries, z.firstAttemptNo, z.version, z.used)

}

func TestExpiredLeaseRejectedBeforeReclaim(t *testing.T) {
	s := storeForTest(t)
	_, r := admitForTest(t, s, "expiry")
	old, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-worker-old", 1)
	if err != nil {
		t.Fatal(err)
	}
	waitForLeaseExpiry(t, s, r.TaskID)
	before := snapshotFor(t, s, r)
	_, err = s.Heartbeat(testContext(t), old, 10)
	if !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("expired heartbeat accepted: %v", err)
	}
	if after := snapshotFor(t, s, r); after != before {
		t.Fatal("expired heartbeat changed persisted state")
	}
	next, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-worker-new", 10)
	if err != nil {
		t.Fatalf("failed reclaim: %v", err)
	}
	if next.LeaseEpoch != old.LeaseEpoch+1 || next.DeliveryCount != 2 || next.AttemptID != old.AttemptID {
		t.Fatalf("redelivery must not create semantic attempt: old=%+v next=%+v", old, next)
	}
	_, err = s.Heartbeat(testContext(t), old, 10)
	if !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("fenced worker renewed lease: %v", err)
	}
	if _, err = s.Heartbeat(testContext(t), next, 10); err != nil {
		t.Fatalf("new owner heartbeat failed: %v", err)
	}
	var budget int
	if err = s.db.QueryRowContext(testContext(t), `SELECT budget_used FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, r.RunID).Scan(&budget); err != nil || budget != 0 {
		t.Fatalf("transport retry improperly charged semantic budget: budget=%d err=%v", budget, err)
	}
}

func TestDurableReceiptLeaseAndBudgetOnReconnect(t *testing.T) {
	s := storeForTest(t)
	_, r := admitForTest(t, s, "reconnect")
	lease, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-worker-reconnect", 10)
	if err != nil {
		t.Fatal(err)
	}
	// Simulate process reconnect with a second independent Go DB pool.
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	again, err := Open(ctx, os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	var state string
	var version, budget, deliveryCount int
	var epoch int64
	var expires time.Time
	err = again.db.QueryRowContext(ctx, `SELECT r.state,r.run_version,r.budget_used,t.lease_epoch,
      t.delivery_count,t.lease_expires_at FROM orchestration.workflow_runs r
      JOIN orchestration.task_deliveries t ON t.run_id=r.run_id
      WHERE r.run_id=$1::uuid`, r.RunID).Scan(&state, &version, &budget, &epoch, &deliveryCount, &expires)
	if err != nil {
		t.Fatal(err)
	}
	if state != "INVESTIGATING" || version != 1 || budget != 0 || epoch != lease.LeaseEpoch || deliveryCount != 1 || !expires.After(time.Now()) {
		t.Fatalf("durable state mismatch: state=%s version=%d budget=%d epoch=%d deliveries=%d expiry=%v", state, version, budget, epoch, deliveryCount, expires)
	}
	bogus := lease
	bogus.ClaimantID = "synthetic-unauthorized"
	if _, err = again.Heartbeat(ctx, bogus, 10); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("wrong owner renewed lease: %v", err)
	}
	bogus = lease
	bogus.LeaseEpoch++
	if _, err = again.Heartbeat(ctx, bogus, 10); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("wrong epoch renewed lease: %v", err)
	}
	if _, err = again.Heartbeat(ctx, lease, 10); err != nil {
		t.Fatalf("correct heartbeat failed: %v", err)
	}
}

// Integration tests intentionally never DELETE via runtime DB role.
// The trusted harness removes only namespaced synthetic data with its admin connection.

// The caller-controlled SubjectKey is NOT the durable uniqueness authority.
// Two command IDs for the same enrolled Jira issue+GitLab project must conflict
// even if the claimant changes subject label, policy epoch and source revision.
func TestAdmissionCanonicalSubjectRejectsSpoofedSubjectKey(t *testing.T) {
	s := storeForTest(t)
	ctx := testContext(t)
	original, first := admitForTest(t, s, "canonical-subject")
	spoof := original
	spoof.CommandID, _ = freshUUID()
	spoof.SubjectKey += ":spoof"
	spoof.PolicyEpoch++
	spoof.SourceFingerprintSHA256 = strings.Repeat("c", 64)
	spoof.RepositoryBaseSHA = strings.Repeat("2", 40)
	if _, err := s.Admit(ctx, spoof); !errors.Is(err, ErrConflict) {
		t.Fatalf("distinct subject label bypassed active identity constraint: %v", err)
	}
	var count int
	err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.workflow_runs
		WHERE jira_site_id=$1 AND jira_project_id=$2 AND jira_issue_id=$3 AND gitlab_project_id=$4`,
		original.JiraSiteID, original.JiraProjectID, original.JiraIssueID, original.GitLabProjectID).Scan(&count)
	if err != nil || count != 1 {
		t.Fatalf("duplicate canonical run count=%d err=%v", count, err)
	}
	var state string
	err = s.db.QueryRowContext(ctx, `SELECT state FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, first.RunID).Scan(&state)
	if err != nil || state != "INVESTIGATING" {
		t.Fatalf("original run changed: %s %v", state, err)
	}
}

func TestAdmissionDifferentGitLabProjectIsSeparateSubject(t *testing.T) {
	s := storeForTest(t)
	original, _ := admitForTest(t, s, "different-project")
	another := original
	another.CommandID, _ = freshUUID()
	another.SubjectKey += ":another-repository"
	another.GitLabProjectID = "synthetic-other-gitlab-project"
	if _, err := s.Admit(testContext(t), another); err != nil {
		t.Fatalf("distinct canonical GitLab project incorrectly blocked: %v", err)
	}
}

func TestAdmissionRejectsSameClaimedHashChangedSource(t *testing.T) {
	s := storeForTest(t)
	original, first := admitForTest(t, s, "same-claimed-hash")
	originalHash, err := original.canonicalPayloadSHA256()
	if err != nil {
		t.Fatal(err)
	}
	changed := original
	changed.SourceFingerprintSHA256 = strings.Repeat("f", 64)
	changed.PayloadSHA256 = originalHash
	if _, err = s.Admit(testContext(t), changed); !errors.Is(err, ErrConflict) {
		t.Fatalf("same command ID, same claimed hash, changed source accepted: %v", err)
	}
	replayed := original
	replayed.PayloadSHA256 = originalHash
	result, err := s.Admit(testContext(t), replayed)
	if err != nil || !result.Replayed || result.RunID != first.RunID {
		t.Fatalf("correct asserted hash of identical command did not replay: %+v %v", result, err)
	}
}

func waitForLeaseExpiry(t *testing.T, s *Store, taskID string) {
	t.Helper()
	testsupport.WaitForLeaseExpiry(t, s.db, taskID)
}
