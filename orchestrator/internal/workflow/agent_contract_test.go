package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fixtureAgentResult(t *testing.T) (AgentTaskV1, []byte) {
	t.Helper()
	id := "11111111-1111-4111-8111-111111111111"
	task := AgentTaskV1{Schema: "asep.agent.v1", RunID: id, TaskID: id, AttemptID: id, OperationID: id, RunVersion: 1, LeaseEpoch: 1, PolicyID: "synthetic-contract", PolicyEpoch: 1, SourceFingerprint: strings.Repeat("a", 64), BaseSHA: strings.Repeat("b", 40), Stage: "IMPLEMENTING", TokenReservation: 128, RemainingSeconds: 30, Scenario: "success"}
	encoded, _ := json.Marshal(task)
	var binding map[string]any
	_ = json.Unmarshal(encoded, &binding)
	for _, key := range []string{"tokenReservation", "remainingSeconds", "scenario"} {
		delete(binding, key)
	}
	result := AgentResultV1{Binding: binding, Model: "deterministic-local-fixture/v1", Classification: "UNTRUSTED_PROPOSAL", Status: "PROPOSED", TokensReserved: 128, Coverage: []string{}, Proposal: map[string]any{"path": "vn-t14-probe-" + id + ".txt", "content": "proposal only\n"}}
	body, _ := json.Marshal(result)
	return task, body
}
func TestAgentResultBindingAndAuthorityDenials(t *testing.T) {
	task, body := fixtureAgentResult(t)
	if _, err := validateAgentResult(task, body); err != nil {
		t.Fatal("valid proposal rejected", err)
	}
	for name, mutate := range map[string]func(map[string]any){
		"stale-version": func(v map[string]any) { v["binding"].(map[string]any)["runVersion"] = 2 },
		"different-attempt": func(v map[string]any) {
			v["binding"].(map[string]any)["attemptId"] = "22222222-2222-4222-8222-222222222222"
		},
		"self-verification":  func(v map[string]any) { v["verified"] = true },
		"unbounded-path":     func(v map[string]any) { v["proposal"].(map[string]any)["path"] = ".gitlab-ci.yml" },
		"cloud-fallback":     func(v map[string]any) { v["model"] = "cloud" },
		"unaccounted-tokens": func(v map[string]any) { v["tokensReserved"] = 1 },
		"claimed-pass":       func(v map[string]any) { v["status"] = "PASS" },
	} {
		t.Run(name, func(t *testing.T) {
			var v map[string]any
			_ = json.Unmarshal(body, &v)
			mutate(v)
			bad, _ := json.Marshal(v)
			if _, err := validateAgentResult(task, bad); !errors.Is(err, ErrAgentProposal) {
				t.Fatal("untrusted authority/binding accepted")
			}
		})
	}
	duplicate := append([]byte(`{"status":"PASS",`), body[1:]...)
	for _, bad := range [][]byte{duplicate, append(body, []byte(" {}")...), []byte(strings.Repeat(" ", 8193))} {
		if _, err := validateAgentResult(task, bad); !errors.Is(err, ErrAgentProposal) {
			t.Fatal("ambiguous/unbounded result accepted")
		}
	}
}
func TestAgentSupervisorPinsAndReapsActualPython(t *testing.T) {
	task, _ := fixtureAgentResult(t)
	script, err := filepath.Abs("../../../agent-runtime/asep_agent/worker.py")
	if err != nil {
		t.Fatal(err)
	}
	source, err := os.ReadFile(script)
	if err != nil {
		t.Fatal(err)
	}
	result, err := RunSyntheticAgent(context.Background(), task, script, SyntheticAgentScriptDigest(source))
	if err != nil || result.Status != "PROPOSED" {
		t.Fatal("actual deterministic Python protocol", err)
	}
	if _, err = RunSyntheticAgent(context.Background(), task, script, strings.Repeat("f", 64)); !errors.Is(err, ErrAgentProposal) {
		t.Fatal("changed adapter executed")
	}
	task.Scenario = "model-unavailable"
	result, err = RunSyntheticAgent(context.Background(), task, script, SyntheticAgentScriptDigest(source))
	if err != nil || result.Status != "MODEL_UNAVAILABLE" || result.Proposal != nil {
		t.Fatal("unavailability invoked fallback", err)
	}
}

func TestAgentUnavailableCannotAdvance(t *testing.T) {
	task, body := fixtureAgentResult(t)
	var result AgentResultV1
	if json.Unmarshal(body, &result) != nil {
		t.Fatal("invalid fixture")
	}
	result.Status = "MODEL_UNAVAILABLE"
	result.Proposal = nil
	body, _ = json.Marshal(result)
	// No database exists here: unavailability must be rejected before any state access.
	if _, err := (&Store{}).AcceptSliceAgentResult(context.Background(), TaskLease{}, task, task.OperationID, body); !errors.Is(err, ErrModelUnavailable) {
		t.Fatal("unavailable result advanced", err)
	}
}
func TestAgentSupervisorRejectsOversizeAndDeadline(t *testing.T) {
	task, _ := fixtureAgentResult(t)
	task.RemainingSeconds = 1
	for name, source := range map[string]string{"oversize": "print('x'*8193)", "deadline": "import time;time.sleep(10)"} {
		t.Run(name, func(t *testing.T) {
			script := filepath.Join(t.TempDir(), "worker.py")
			if err := os.WriteFile(script, []byte(source), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := RunSyntheticAgent(t.Context(), task, script, SyntheticAgentScriptDigest([]byte(source))); !errors.Is(err, ErrAgentProposal) {
				t.Fatal("unbounded child accepted", err)
			}
		})
	}
}

func TestAgentUnresolvedInvestigationCannotAdvance(t *testing.T) {
	task, body := fixtureAgentResult(t)
	task.Stage = "INVESTIGATING"
	var result AgentResultV1
	_ = json.Unmarshal(body, &result)
	result.Binding["stage"] = task.Stage
	result.Coverage = []string{"synthetic-ticket", "pinned-base-tree"}
	result.Proposal = map[string]any{"finding": "blocked investigation", "missingContext": []string{"base unavailable"}}
	body, _ = json.Marshal(result)
	if _, err := validateAgentResult(task, body); !errors.Is(err, ErrAgentProposal) {
		t.Fatal("missing context silently advanced", err)
	}
}
