//go:build integration

package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// This peer exists only in backend test-classes and communicates over owned pipes.
// Go holds its DB capability; Java receives only synthetic fixture config, never SQL,
// Docker credentials, a service token, or the disposable-instance proof.
type manualJavaPeer struct {
	process *exec.Cmd
	input   io.WriteCloser
	encoder *json.Encoder
	decoder *json.Decoder
	waited  bool
}

type manualJavaMessage struct {
	Kind    string           `json:"kind"`
	Command Admission        `json:"command"`
	Reads   int              `json:"reads"`
	State   string           `json:"state"`
	Receipt AdmissionReceipt `json:"receipt"`
}

func startManualJavaPeer(t *testing.T, c Admission, body string) *manualJavaPeer {
	t.Helper()
	classpathFile := os.Getenv("ASEP_VN_T12_JAVA_CLASSPATH_FILE")
	classpath, err := os.ReadFile(classpathFile)
	if err != nil || len(classpath) == 0 || os.Getenv("ASEP_VN_T12_JAVA_BIN") == "" {
		t.Fatal("private Java fixture missing: run scripts/ci_verify.sh in all or manual-admission mode")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	command := exec.CommandContext(ctx, os.Getenv("ASEP_VN_T12_JAVA_BIN"), "-cp", string(classpath),
		"io.github.qhung13.asep.admission.ManualAdmissionProcessFixture")
	command.Env = []string{"LANG=C.UTF-8", "TZ=UTC"}
	command.Stderr = io.Discard
	command.WaitDelay = time.Second
	input, err := command.StdinPipe()
	if err != nil {
		t.Fatal("fixture input unavailable")
	}
	output, err := command.StdoutPipe()
	if err != nil {
		t.Fatal("fixture output unavailable")
	}
	if err := command.Start(); err != nil {
		t.Fatal("private Java peer failed to start")
	}
	p := &manualJavaPeer{process: command, input: input, encoder: json.NewEncoder(input), decoder: json.NewDecoder(io.LimitReader(output, 65536))}
	t.Cleanup(func() {
		_ = input.Close()
		if !p.waited {
			_ = command.Process.Kill()
			_ = command.Wait()
		}
	})
	p.send(t, map[string]string{"issueId": c.JiraIssueID, "projectId": c.JiraProjectID,
		"prefix": os.Getenv("ASEP_VN_T10_TEST_PREFIX"), "body": body})
	return p
}

func (p *manualJavaPeer) send(t *testing.T, value any) {
	t.Helper()
	if p.encoder.Encode(value) != nil {
		t.Fatal("private Java peer send failed")
	}
}

func (p *manualJavaPeer) receive(t *testing.T, kind string) manualJavaMessage {
	t.Helper()
	var m manualJavaMessage
	if p.decoder.Decode(&m) != nil || m.Kind != kind {
		t.Fatal("private Java peer protocol failed at", kind)
	}
	return m
}

func (p *manualJavaPeer) finish(t *testing.T) {
	t.Helper()
	_ = p.input.Close()
	err := p.process.Wait()
	p.waited = true
	if err != nil {
		t.Fatal("private Java peer did not terminate cleanly")
	}
}

func (p *manualJavaPeer) prepare(t *testing.T, c Admission, slot string) {
	t.Helper()
	p.send(t, map[string]string{"action": "prepare", "slot": slot, "operationId": c.CommandID})
	if m := p.receive(t, "prepared"); m.Command != c {
		t.Fatal("Java normalization/identity differs from Go's independently computed fixture contract")
	}
}

func (p *manualJavaPeer) policy(t *testing.T, mode string) {
	t.Helper()
	p.send(t, map[string]string{"action": "policy", "mode": mode})
	p.receive(t, "policy-set")
}

func (p *manualJavaPeer) action(t *testing.T, kind, slot string, c Admission) {
	t.Helper()
	p.send(t, map[string]string{"action": kind, "slot": slot})
	if m := p.receive(t, kind); m.Command != c {
		t.Fatal("Java replaced the original command identity")
	}
}

func (p *manualJavaPeer) answer(t *testing.T, state string, receipt AdmissionReceipt, reads int) {
	t.Helper()
	answer := map[string]any{"state": state}
	if state == "ACCEPTED" {
		answer["receipt"] = map[string]any{"runId": receipt.RunID, "taskId": receipt.TaskID,
			"attemptId": receipt.AttemptID, "replayed": receipt.Replayed}
	}
	p.send(t, answer)
	p.outcome(t, state, receipt, reads)
}

func manualJavaCommand(c Admission) map[string]any {
	return map[string]any{"commandId": c.CommandID, "subjectKey": c.SubjectKey, "actorId": c.ActorID,
		"jiraSiteId": c.JiraSiteID, "jiraProjectId": c.JiraProjectID, "jiraIssueId": c.JiraIssueID,
		"gitLabProjectId": c.GitLabProjectID, "repositoryBaseSha": c.RepositoryBaseSHA,
		"targetBranch": c.TargetBranch, "observedTargetHeadSha": c.ObservedTargetHeadSHA,
		"sourceFingerprintSha256": c.SourceFingerprintSHA256, "policyId": c.PolicyID,
		"policyEpoch": c.PolicyEpoch, "budgetLimit": c.BudgetLimit}
}

func (p *manualJavaPeer) outcome(t *testing.T, state string, receipt AdmissionReceipt, reads int) {
	t.Helper()
	m := p.receive(t, "outcome")
	if m.State != state || m.Reads != reads || m.Receipt != receipt {
		t.Fatal("Java lost exact failure classification, receipt or original snapshot")
	}
}

func manualJavaFixture(t *testing.T) (Admission, syntheticProfile, string) {
	t.Helper()
	c, profile := manualTicketFixture(t)
	c.SubjectKey = os.Getenv("ASEP_VN_T10_TEST_PREFIX") + ":manual:" + c.JiraIssueID
	var payload map[string]any
	if json.Unmarshal([]byte(readManualTicketVector(t).ResponseBody), &payload) != nil {
		t.Fatal("fixture unavailable")
	}
	payload["id"] = c.JiraIssueID
	body, err := json.Marshal(payload)
	if err != nil {
		t.Fatal("fixture encoding failed")
	}
	return c, profile, string(body)
}

// Independent fresh PostgreSQL connection, separate from the submitting Store and
// from Java. Count the entire manual subject, including accidental replacement IDs.
func observeManualBundle(t *testing.T, c Admission, want int) {
	t.Helper()
	s := storeForTest(t)
	for _, table := range []string{"workflow_runs", "stage_attempts", "task_deliveries", "workflow_outbox", "workflow_transitions"} {
		query := `SELECT count(*) FROM orchestration.` + table + ` WHERE run_id IN
			(SELECT run_id FROM orchestration.workflow_runs WHERE subject_key=$1 OR command_id=$2::uuid)`
		var count int
		if err := s.db.QueryRowContext(testContext(t), query, c.SubjectKey, c.CommandID).Scan(&count); err != nil || count != want {
			t.Fatalf("independent PostgreSQL %s count: want %d got %d (query failed=%v)", table, want, count, err != nil)
		}
	}
	if want == 1 {
		var valid bool
		err := s.db.QueryRowContext(testContext(t), `SELECT actor_id=$2 AND trigger_kind='MANUAL'
			AND jira_site_id=$3 AND jira_project_id=$4 AND jira_issue_id=$5 AND gitlab_project_id=$6
			AND source_fingerprint_sha256=$7 AND policy_id=$8 AND policy_epoch=$9
			AND budget_limit=$10 AND budget_used=0 AND run_version=1 AND state='INVESTIGATING'
			FROM orchestration.workflow_runs WHERE command_id=$1::uuid`, c.CommandID, c.ActorID, c.JiraSiteID,
			c.JiraProjectID, c.JiraIssueID, c.GitLabProjectID, c.SourceFingerprintSHA256, c.PolicyID, c.PolicyEpoch, c.BudgetLimit).Scan(&valid)
		if err != nil || !valid {
			t.Fatal("independent durable identity/budget/authority assertion failed")
		}
	}
}

func TestManualTicketJavaGoFailClosedIntegration(t *testing.T) {
	// Enforce disposable-instance proof before launching any child or touching data.
	s := storeForTest(t)
	for _, scenario := range []string{"valid-duplicate", "denied-project", "missing-mapping", "missing-policy", "revoked-policy", "unavailable-policy", "revoked-after-prepare", "unavailable-after-prepare", "changed-snapshot", "conflicting-payload", "go-unavailable", "lost-response-restart"} {
		t.Run(scenario, func(t *testing.T) {
			c, profile, body := manualJavaFixture(t)
			if scenario == "denied-project" {
				c.JiraProjectID = "20001"
			}
			p := startManualJavaPeer(t, c, body)
			var denial string
			switch scenario {
			case "missing-mapping":
				denial = "unmapped"
			case "missing-policy":
				denial = "missing"
			case "revoked-policy":
				denial = "revoked"
			case "unavailable-policy":
				denial = "unavailable"
			}
			if denial != "" {
				p.policy(t, denial)
			}
			if denial != "" || scenario == "denied-project" {
				p.send(t, map[string]string{"action": "prepare", "slot": "original", "operationId": c.CommandID})
				// An attempted Go call would emit "submit", causing this assertion to fail.
				p.outcome(t, "DENIED", AdmissionReceipt{}, 0)
				p.finish(t)
				observeManualBundle(t, c, 0)
				return
			}
			p.prepare(t, c, "original")
			if scenario == "revoked-after-prepare" || scenario == "unavailable-after-prepare" {
				mode := "revoked"
				if scenario == "unavailable-after-prepare" {
					mode = "unavailable"
				}
				p.policy(t, mode)
				p.send(t, map[string]string{"action": "submit", "slot": "original"})
				p.outcome(t, "DENIED", AdmissionReceipt{}, 1)
				p.send(t, map[string]string{"action": "reconcile", "slot": "original"})
				p.outcome(t, "UNKNOWN", AdmissionReceipt{}, 1)
				p.finish(t)
				observeManualBundle(t, c, 0)
				return
			}
			p.action(t, "submit", "original", c)
			if scenario == "go-unavailable" {
				// Actual private transport EOF before Go admission; Java cannot fabricate a receipt.
				_ = p.input.Close()
				p.outcome(t, "UNKNOWN", AdmissionReceipt{}, 1)
				p.finish(t)
				observeManualBundle(t, c, 0)
				return
			}
			r, err := s.admitBounded(testContext(t), c, profile)
			if err != nil || r.Replayed {
				t.Fatal("initial Java-to-Go durable admission failed")
			}
			observeManualBundle(t, c, 1)
			want := r
			want.Replayed = true
			if scenario == "lost-response-restart" {
				// Persist only the original synthetic command in the test-owned client journal.
				journal := t.TempDir() + "/original-command.json"
				encoded, err := json.Marshal(c)
				if err != nil || os.WriteFile(journal, encoded, 0600) != nil {
					t.Fatal("test-owned original command journal failed")
				}
				// Drop the pipe AFTER Go COMMIT, instead of returning a mocked timeout result.
				_ = p.input.Close()
				p.outcome(t, "UNKNOWN", AdmissionReceipt{}, 1)
				p.finish(t)
				p = startManualJavaPeer(t, c, body) // Real JVM restart.
				originalBytes, err := os.ReadFile(journal)
				var original Admission
				if err != nil || json.Unmarshal(originalBytes, &original) != nil || original != c {
					t.Fatal("original journal identity changed across restart")
				}
				p.send(t, map[string]any{"action": "resume", "slot": "original", "command": manualJavaCommand(original)})
				if m := p.receive(t, "reconcile"); m.Command != c {
					t.Fatal("restarted client submitted new work or changed original identity")
				}
				lookup, err := storeForTest(t).reconcileBoundedAdmission(testContext(t), original, profile)
				if err != nil || lookup != want {
					t.Fatal("original command did not reconcile after client restart")
				}
				p.answer(t, "ACCEPTED", lookup, 1)
				p.action(t, "submit", "original", original)
				replay, err := s.admitBounded(testContext(t), original, profile)
				if err != nil || replay != want {
					t.Fatal("retry after confirmed restart recovery duplicated or changed admission")
				}
				p.answer(t, "ACCEPTED", replay, 1)
				// A changed snapshot cannot reconstruct unresolved authority on a later restart.
				p.finish(t)
				p = startManualJavaPeer(t, c, strings.Replace(body, "café", "changed", 1))
				p.send(t, map[string]any{"action": "resume", "slot": "original", "command": manualJavaCommand(original)})
				p.outcome(t, "UNKNOWN", AdmissionReceipt{}, 1)
			} else {
				p.answer(t, "ACCEPTED", r, 1)
				switch scenario {
				case "valid-duplicate":
					p.action(t, "submit", "original", c)
					replay, err := s.admitBounded(testContext(t), c, profile)
					if err != nil || replay != want {
						t.Fatal("duplicate command did not return original receipt")
					}
					p.answer(t, "ACCEPTED", replay, 1)
				case "changed-snapshot", "conflicting-payload":
					changed := c
					reads := 2
					if scenario == "changed-snapshot" {
						body = strings.Replace(body, "café", "changed", 1)
						changed.SourceFingerprintSHA256 = manualTicketFingerprint(changed, []byte(body), readManualTicketVector(t).NormalizedDescription)
						p.send(t, map[string]string{"action": "body", "body": body})
						p.receive(t, "body-set")
					} else {
						changed.GitLabProjectID = "43"
						p.policy(t, "remapped")
					}
					p.prepare(t, changed, "changed")
					p.action(t, "submit", "changed", changed)
					if got, err := s.admitBounded(testContext(t), changed, profile); !errors.Is(err, ErrConflict) || errors.Is(err, ErrResultUnknown) || got != (AdmissionReceipt{}) {
						t.Fatal("changed command was not a definitive conflict")
					}
					p.answer(t, "CONFLICT", AdmissionReceipt{}, reads)
				}
			}
			p.finish(t)
			observeManualBundle(t, c, 1)
		})
	}
}
