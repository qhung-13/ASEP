//go:build integration

package workflow

import (
	"errors"
	"fmt"
	"sync"
	"testing"
)

func TestConcurrentAdmissionReplayAndPayloadConflict(t *testing.T) {
	for _, conflicting := range []bool{false, true} {
		name := "identical-replay"
		if conflicting {
			name = "conflicting-payload"
		}
		t.Run(name, func(t *testing.T) {
			stores := []*Store{storeForTest(t), storeForTest(t)}
			command := admissionForTest(t, name)
			const callers = 16
			type response struct {
				command Admission
				receipt AdmissionReceipt
				err     error
			}
			results := make(chan response, callers)
			start := make(chan struct{})
			var group sync.WaitGroup
			for i := 0; i < callers; i++ {
				candidate := command
				if conflicting && i%2 == 1 {
					candidate.ActorID = "synthetic-conflicting-owner"
				}
				group.Add(1)
				go func(i int, candidate Admission) {
					defer group.Done()
					<-start
					receipt, err := stores[i%2].Admit(testContext(t), candidate)
					results <- response{candidate, receipt, err}
				}(i, candidate)
			}
			close(start)
			group.Wait()
			close(results)
			var original AdmissionReceipt
			var acceptedCommand Admission
			var accepted []AdmissionReceipt
			created, conflicts := 0, 0
			for result := range results {
				if errors.Is(result.err, ErrConflict) {
					conflicts++
					continue
				}
				if result.err != nil {
					t.Fatalf("RF-01: unexpected admission outcome: %v", result.err)
				}
				accepted = append(accepted, result.receipt)
				if !result.receipt.Replayed {
					created++
					original, acceptedCommand = result.receipt, result.command
				}
			}
			expectedConflicts := 0
			if conflicting {
				expectedConflicts = callers / 2
			}
			if created != 1 || conflicts != expectedConflicts || len(accepted) != callers-expectedConflicts {
				t.Fatalf("RF-01: created=%d conflicts=%d accepted=%d", created, conflicts, len(accepted))
			}
			for _, receipt := range accepted {
				if receipt.RunID != original.RunID || receipt.TaskID != original.TaskID || receipt.AttemptID != original.AttemptID {
					t.Fatal("RF-01: matching replay returned a different logical receipt")
				}
			}
			// Reopen yet another independent pool after the concurrent writers finish.
			reopened := storeForTest(t)
			replay, err := reopened.Admit(testContext(t), acceptedCommand)
			if err != nil || !replay.Replayed || replay.RunID != original.RunID || replay.TaskID != original.TaskID || replay.AttemptID != original.AttemptID {
				t.Fatal("RF-01: independent-connection replay did not recover the original receipt")
			}
			assertSingleAdmission(t, reopened, acceptedCommand, original)
			t.Logf("RF-01 observed: callers=%d created=%d matching=%d conflicts=%d; one run/attempt/task/transition/outbox; original receipt preserved", callers, created, len(accepted), conflicts)
		})
	}
}

func TestConcurrentAdmissionCanonicalSubjectSingleActiveRun(t *testing.T) {
	stores := []*Store{storeForTest(t), storeForTest(t)}
	base := admissionForTest(t, "canonical-contention")
	const callers = 16
	type response struct {
		command Admission
		receipt AdmissionReceipt
		err     error
	}
	results := make(chan response, callers)
	start := make(chan struct{})
	var group sync.WaitGroup
	for i := 0; i < callers; i++ {
		candidate := base
		var err error
		candidate.CommandID, err = freshUUID()
		if err != nil {
			t.Fatal("synthetic command identity unavailable")
		}
		candidate.SubjectKey += fmt.Sprintf(":label-%d", i)
		candidate.PolicyEpoch += int64(i)
		candidate.SourceFingerprintSHA256 = fmt.Sprintf("%064x", i+1)
		candidate.RepositoryBaseSHA = fmt.Sprintf("%040x", i+1)
		group.Add(1)
		go func(i int, candidate Admission) {
			defer group.Done()
			<-start
			receipt, err := stores[i%2].Admit(testContext(t), candidate)
			results <- response{candidate, receipt, err}
		}(i, candidate)
	}
	close(start)
	group.Wait()
	close(results)
	winners, conflicts := 0, 0
	var winner response
	for result := range results {
		if errors.Is(result.err, ErrConflict) {
			conflicts++
		} else if result.err == nil && !result.receipt.Replayed {
			winners++
			winner = result
		} else {
			t.Fatalf("RF-02: unexpected canonical contention outcome: %v", result.err)
		}
	}
	if winners != 1 || conflicts != callers-1 {
		t.Fatalf("RF-02: winners=%d conflicts=%d", winners, conflicts)
	}
	assertSingleAdmission(t, stores[0], winner.command, winner.receipt)
	t.Logf("RF-02 active-subject observed: winners=%d conflicts=%d; changed labels/source/policy did not bypass V3; terminal rerun policy remains OPEN", winners, conflicts)
}

func assertSingleAdmission(t *testing.T, store *Store, command Admission, receipt AdmissionReceipt) {
	t.Helper()
	var count int
	if err := store.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.workflow_runs
        WHERE jira_site_id=$1 AND jira_project_id=$2 AND jira_issue_id=$3 AND gitlab_project_id=$4`,
		command.JiraSiteID, command.JiraProjectID, command.JiraIssueID, command.GitLabProjectID).Scan(&count); err != nil || count != 1 {
		t.Fatal("concurrent admission persisted more than one canonical workflow")
	}
	var digest string
	if err := store.db.QueryRowContext(testContext(t), `SELECT command_payload_sha256
        FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, receipt.RunID).Scan(&digest); err != nil {
		t.Fatal("persisted canonical admission digest unavailable")
	}
	expected, err := command.canonicalPayloadSHA256()
	if err != nil || digest != expected {
		t.Fatal("concurrent admission changed the winning canonical payload")
	}
	z := snapshotFor(t, store, receipt)
	if z.state != "INVESTIGATING" || z.version != 1 || z.used != 0 || z.limit != command.BudgetLimit ||
		z.tasks != 1 || z.attempts != 1 || z.transitions != 1 || z.outbox != 1 || z.outstanding != 1 ||
		z.firstTaskStatus != "QUEUED" || z.firstTaskEpoch != 0 || z.firstTaskDeliveries != 0 ||
		z.firstAttemptState != "PENDING" || z.firstAttemptNo != 1 {
		t.Fatalf("concurrent admission partial or duplicate bundle: %+v", z)
	}
}
