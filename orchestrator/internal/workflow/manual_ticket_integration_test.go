//go:build integration

package workflow

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func manualTicketFixture(t *testing.T) (Admission, syntheticProfile) {
	t.Helper()
	v := readManualTicketVector(t)
	c := admissionForTest(t, "manual-jira")
	number, err := strconv.ParseUint(strings.ReplaceAll(c.CommandID, "-", "")[:15], 16, 64)
	if err != nil {
		t.Fatal(err)
	}
	c.JiraSiteID, c.JiraProjectID, c.JiraIssueID, c.GitLabProjectID = "synthetic-site", "10001", strconv.FormatUint(number+1, 10), "42"
	c.PolicyID = "synthetic-manual-v1"
	var payload map[string]any
	if err := json.Unmarshal([]byte(v.ResponseBody), &payload); err != nil {
		t.Fatal(err)
	}
	payload["id"] = c.JiraIssueID
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	c.SourceFingerprintSHA256 = manualTicketFingerprint(c, raw, v.NormalizedDescription)
	return c, syntheticProfile{2, 1, 1, true} // Explicit fixture limits, never production defaults.
}

func TestManualTicketDurableReplayAndCancellation(t *testing.T) {
	s := storeForTest(t)
	c, profile := manualTicketFixture(t)
	r, err := s.admitBounded(testContext(t), c, profile)
	if err != nil {
		t.Fatal(err)
	}
	before := snapshotFor(t, s, r)
	var bound bool
	if err := s.db.QueryRowContext(testContext(t), `SELECT actor_id=$2 AND trigger_kind='MANUAL'
        AND jira_site_id=$3 AND jira_project_id=$4 AND jira_issue_id=$5 AND gitlab_project_id=$6
        AND source_fingerprint_sha256=$7 AND policy_id=$8 AND policy_epoch=$9 AND budget_limit=$10
        FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, r.RunID, c.ActorID, c.JiraSiteID, c.JiraProjectID,
		c.JiraIssueID, c.GitLabProjectID, c.SourceFingerprintSHA256, c.PolicyID, c.PolicyEpoch, c.BudgetLimit).Scan(&bound); err != nil || !bound {
		t.Fatal("manual actor/trigger/source/policy provenance not durably bound", err)
	}
	want := r
	want.Replayed = true
	if got, err := storeForTest(t).reconcileBoundedAdmission(testContext(t), c, profile); err != nil || got != want {
		t.Fatal("manual command identity not recovered after reconnect", err)
	}
	changed := c
	changed.SourceFingerprintSHA256 = strings.Repeat("e", 64)
	if _, err := s.admitBounded(testContext(t), changed, profile); !errors.Is(err, ErrConflict) || errors.Is(err, ErrResultUnknown) {
		t.Fatal("same operation with changed ticket snapshot was not definitive conflict", err)
	}
	changed.CommandID, _ = freshUUID()
	if _, err := s.admitBounded(testContext(t), changed, profile); !errors.Is(err, ErrConflict) {
		t.Fatal("changed revision bypassed one-active canonical Jira/repository subject", err)
	}
	if snapshotFor(t, s, r) != before {
		t.Fatal("rejected admission changed progress/budget")
	}
	queued := TaskLease{RunID: r.RunID, TaskID: r.TaskID, AttemptID: r.AttemptID, Stage: "INVESTIGATING"}
	if _, err := s.requestCancellation(testContext(t), cancellationForTest(t, c, queued, 1)); err != nil {
		t.Fatal(err)
	}
	cancelled := snapshotFor(t, s, r)
	if got, err := s.admitBounded(testContext(t), c, profile); err != nil || got != want || snapshotFor(t, s, r) != cancelled {
		t.Fatal("historical admission replay reactivated cancelled authority", err)
	}
	if _, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-manual-late", 60); !errors.Is(err, ErrNoTask) {
		t.Fatal("cancellation did not fence execution", err)
	}
}

func TestManualTicketConcurrentCanonicalSubject(t *testing.T) {
	for _, sameOperation := range []bool{true, false} {
		t.Run(strconv.FormatBool(sameOperation), func(t *testing.T) {
			s := storeForTest(t)
			c, profile := manualTicketFixture(t)
			var wg sync.WaitGroup
			receipts := make(chan AdmissionReceipt, 8)
			for range 8 {
				command := c
				if !sameOperation {
					command.CommandID, _ = freshUUID()
				}
				wg.Go(func() {
					r, err := s.admitBounded(testContext(t), command, profile)
					if err == nil {
						receipts <- r
					} else if sameOperation || !errors.Is(err, ErrConflict) || errors.Is(err, ErrResultUnknown) {
						t.Error("concurrent manual admission outcome invalid", err)
					}
				})
			}
			wg.Wait()
			close(receipts)
			var original AdmissionReceipt
			count := 0
			for r := range receipts {
				r.Replayed = false
				if count == 0 {
					original = r
				}
				if r != original {
					t.Fatal("duplicate logical workflow admitted")
				}
				count++
			}
			if (sameOperation && count != 8) || (!sameOperation && count != 1) {
				t.Fatal("unexpected admission winner count")
			}
			got := snapshotFor(t, s, original)
			if got.tasks != 1 || got.attempts != 1 || got.outbox != 1 || got.transitions != 1 || got.used != 0 || got.version != 1 {
				t.Fatal("manual admission bundle not atomic/exactly one logical result")
			}
		})
	}
}

func TestManualTicketUncertainCommitRecovery(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s := storeForTest(t)
			c, profile := manualTicketFixture(t)
			url, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), url)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = faulty.Close() })
			r, err := faulty.admitBounded(testContext(t), c, profile)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("manual admission COMMIT fault missing")
			}
			if mode == "after-confirm" {
				if err != nil || !r.Replayed {
					t.Fatal("confirmed manual admission not reconciled", err)
				}
			} else if !errors.Is(err, ErrResultUnknown) || r != (AdmissionReceipt{}) {
				t.Fatal("uncertain manual admission falsely acknowledged", err)
			}
			if mode == "before-forward" {
				var count int
				if err := s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.workflow_runs WHERE command_id=$1::uuid`, c.CommandID).Scan(&count); err != nil || count != 0 {
					t.Fatal("uncommitted manual intake persisted", err)
				}
				return
			}
			got, err := s.reconcileBoundedAdmission(testContext(t), c, profile)
			if err != nil {
				t.Fatal(err)
			}
			before := snapshotFor(t, s, got)
			for range 3 {
				if replay, err := s.reconcileBoundedAdmission(testContext(t), c, profile); err != nil || replay != got {
					t.Fatal("original admission recovery changed identity", err)
				}
			}
			if snapshotFor(t, s, got) != before || before.used != 0 || before.tasks != 1 || before.outbox != 1 {
				t.Fatal("reconciliation duplicated work or debit")
			}
		})
	}
}
