package workflow

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

var ErrAgentProposal = errors.New("untrusted agent proposal rejected")
var ErrModelUnavailable = errors.New("local fixture model unavailable; no fallback")

// AgentResultV1 is explicitly untrusted. It cannot contain a verification/review
// receipt, authorization flag, remote operation or caller-selected successor.
type AgentResultV1 struct {
	Binding        map[string]any `json:"binding"`
	Model          string         `json:"model"`
	Classification string         `json:"classification"`
	Status         string         `json:"status"`
	TokensReserved int            `json:"tokensReserved"`
	Coverage       []string       `json:"coverage"`
	Proposal       map[string]any `json:"proposal"`
}

func uniqueAgentJSON(d *json.Decoder, depth int) error {
	if depth > 8 {
		return ErrAgentProposal
	}
	value, err := d.Token()
	if err != nil {
		return err
	}
	delimiter, ok := value.(json.Delim)
	if !ok {
		return nil
	}
	switch delimiter {
	case '{':
		seen := map[string]bool{}
		for d.More() {
			key, err := d.Token()
			if err != nil {
				return err
			}
			name, ok := key.(string)
			if !ok || seen[name] {
				return ErrAgentProposal
			}
			seen[name] = true
			if err = uniqueAgentJSON(d, depth+1); err != nil {
				return err
			}
		}
	case '[':
		for d.More() {
			if err = uniqueAgentJSON(d, depth+1); err != nil {
				return err
			}
		}
	default:
		return ErrAgentProposal
	}
	_, err = d.Token()
	return err
}
func validateAgentResult(task AgentTaskV1, body []byte) (AgentResultV1, error) {
	if len(body) > 8192 || task.Schema != "asep.agent.v1" || task.TokenReservation != 128 {
		return AgentResultV1{}, ErrAgentProposal
	}
	scan := json.NewDecoder(bytes.NewReader(body))
	if uniqueAgentJSON(scan, 0) != nil {
		return AgentResultV1{}, ErrAgentProposal
	}
	if _, err := scan.Token(); err != io.EOF {
		return AgentResultV1{}, ErrAgentProposal
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var r AgentResultV1
	if decoder.Decode(&r) != nil {
		return r, ErrAgentProposal
	}
	original, _ := json.Marshal(task)
	var expected map[string]any
	_ = json.Unmarshal(original, &expected)
	for _, key := range []string{"tokenReservation", "remainingSeconds", "scenario"} {
		delete(expected, key)
	}
	wanted, _ := json.Marshal(expected)
	actual, _ := json.Marshal(r.Binding)
	if !bytes.Equal(wanted, actual) || r.Model != "deterministic-local-fixture/v1" || r.Classification != "UNTRUSTED_PROPOSAL" || r.TokensReserved != task.TokenReservation {
		return r, ErrAgentProposal
	}
	if r.Status == "MODEL_UNAVAILABLE" && r.Proposal == nil {
		return r, nil
	}
	if r.Status != "PROPOSED" {
		return r, ErrAgentProposal
	}
	switch task.Stage {
	case "INVESTIGATING":
		finding, ok := r.Proposal["finding"].(string)
		missing, okMissing := r.Proposal["missingContext"].([]any)
		if len(r.Proposal) != 2 || !ok || len(finding) < 1 || len(finding) > 256 || !okMissing || len(missing) != 0 || len(r.Coverage) != 2 || r.Coverage[0] != "synthetic-ticket" || r.Coverage[1] != "pinned-base-tree" {
			return r, ErrAgentProposal
		}
	case "PLANNING":
		paths, ok := r.Proposal["allowedPaths"].([]any)
		acceptance, okText := r.Proposal["acceptance"].(string)
		if len(r.Proposal) != 2 || !ok || len(paths) != 1 || paths[0] != "vn-t14-probe-"+task.OperationID+".txt" || !okText || len(acceptance) < 1 || len(acceptance) > 256 {
			return r, ErrAgentProposal
		}
	case "IMPLEMENTING":
		content, ok := r.Proposal["content"].(string)
		if len(r.Proposal) != 2 || r.Proposal["path"] != "vn-t14-probe-"+task.OperationID+".txt" || !ok || len(content) < 1 || len(content) > 2048 {
			return r, ErrAgentProposal
		}
		for _, b := range []byte(content) {
			if b > 127 || b == 0 {
				return r, ErrAgentProposal
			}
		}
	default:
		return r, ErrAgentProposal
	}
	return r, nil
}

// RunSyntheticAgent snapshots the reviewed deterministic adapter and executes it
// with no service/DB/credential environment. It has no real-provider mode. This
// controlled CI process is not evidence of deployed untrusted SDK isolation.
func RunSyntheticAgent(ctx context.Context, task AgentTaskV1, script, expectedSHA256 string) (AgentResultV1, error) {
	if task.RemainingSeconds < 1 || task.RemainingSeconds > 300 || !isLowerHex(expectedSHA256, 64) {
		return AgentResultV1{}, ErrAgentProposal
	}
	source, err := os.ReadFile(script)
	if err != nil || len(source) > 65536 {
		return AgentResultV1{}, ErrAgentProposal
	}
	hash := sha256.Sum256(source)
	if hex.EncodeToString(hash[:]) != expectedSHA256 {
		return AgentResultV1{}, ErrAgentProposal
	}
	directory, err := os.MkdirTemp("", "asep-synthetic-agent-")
	if err != nil {
		return AgentResultV1{}, err
	}
	defer os.RemoveAll(directory)
	snapshot := filepath.Join(directory, "worker.py")
	if err = os.WriteFile(snapshot, source, 0600); err != nil {
		return AgentResultV1{}, err
	}
	duration := 5 * time.Second
	if remaining := time.Duration(task.RemainingSeconds) * time.Second; remaining < duration {
		duration = remaining
	}
	ctx, cancel := context.WithTimeout(ctx, duration)
	defer cancel()
	payload, err := json.Marshal(task)
	if err != nil {
		return AgentResultV1{}, ErrAgentProposal
	}
	command := exec.CommandContext(ctx, "/usr/bin/python3", "-I", "-B", snapshot, "--synthetic")
	command.Dir = directory
	command.Env = []string{"LANG=C.UTF-8"}
	command.Stdin = bytes.NewReader(payload)
	command.Stderr = io.Discard
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	command.Cancel = func() error { return syscall.Kill(-command.Process.Pid, syscall.SIGKILL) }
	command.WaitDelay = time.Second
	output, err := command.StdoutPipe()
	if err != nil {
		return AgentResultV1{}, err
	}
	if err = command.Start(); err != nil {
		return AgentResultV1{}, err
	}
	body, readErr := io.ReadAll(io.LimitReader(output, 8193))
	if len(body) > 8192 || readErr != nil {
		_ = syscall.Kill(-command.Process.Pid, syscall.SIGKILL)
	}
	err = command.Wait() // The producer is reaped before any candidate sealing.
	if err != nil || readErr != nil || len(body) > 8192 {
		return AgentResultV1{}, ErrAgentProposal
	}
	return validateAgentResult(task, body)
}

// AcceptSliceAgentResult validates the worker protocol and current durable input
// binding before computing its own proposal reference. A worker-supplied digest
// or "tests passed" cannot reach the stage transition path.
func (s *Store) AcceptSliceAgentResult(ctx context.Context, lease TaskLease, task AgentTaskV1, operation string, body []byte, sealedEvidenceID ...string) (advanceReceipt, error) {
	result, err := validateAgentResult(task, body)
	if err != nil {
		return advanceReceipt{}, err
	}
	if result.Status == "MODEL_UNAVAILABLE" {
		return advanceReceipt{}, ErrModelUnavailable
	}
	if task.RunID != lease.RunID || task.TaskID != lease.TaskID || task.AttemptID != lease.AttemptID || task.LeaseEpoch != lease.LeaseEpoch || task.Stage != lease.Stage {
		return advanceReceipt{}, ErrAgentProposal
	}
	if len(sealedEvidenceID) > 1 || task.Stage != "IMPLEMENTING" && len(sealedEvidenceID) != 0 {
		return advanceReceipt{}, ErrAgentProposal
	}
	candidateID := ""
	if task.Stage == "IMPLEMENTING" {
		if len(sealedEvidenceID) != 1 || !canonicalUUID(sealedEvidenceID[0]) {
			return advanceReceipt{}, ErrDeliveryDenied
		}
		candidateID = sealedEvidenceID[0]
	}
	var bound bool
	err = s.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.workflow_runs WHERE run_id=$1::uuid AND command_id=$2::uuid AND policy_id=$3 AND policy_epoch=$4 AND repository_base_sha=$5 AND source_fingerprint_sha256=$6)`, lease.RunID, task.OperationID, task.PolicyID, task.PolicyEpoch, task.BaseSHA, task.SourceFingerprint).Scan(&bound)
	if err != nil || !bound {
		return advanceReceipt{}, ErrAgentProposal
	}
	proposal, err := json.Marshal(result.Proposal)
	if err != nil {
		return advanceReceipt{}, ErrAgentProposal
	}
	hash := sha256.Sum256(proposal)
	c := SliceAdvance{OperationID: operation, Lease: lease, ExpectedRunVersion: task.RunVersion, PolicyEpoch: task.PolicyEpoch, SourceFingerprint: task.SourceFingerprint, EvidenceRef: "synthetic:proposal:" + hex.EncodeToString(hash[:]), SealedCandidateEvidenceID: candidateID}
	return s.AdvanceSyntheticSlice(ctx, c)
}

// Used by tests and supervised CLI adapters; no transcript/source is logged.
func SyntheticAgentScriptDigest(body []byte) string {
	h := sha256.Sum256(body)
	return strings.ToLower(hex.EncodeToString(h[:]))
}
