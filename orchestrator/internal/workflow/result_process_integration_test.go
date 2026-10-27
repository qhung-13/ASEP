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
	"syscall"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

const resultControllerFlag = "ASEP_VN_T11_RESULT_CONTROLLER"

type resultControllerConfig struct {
	DatabaseURL, Nonce, Prefix string
	Command                    investigationAdvance
}

// Only this integration test binary has the trusted Store-controller role.
// It is not a worker/agent or a production result endpoint. An environment flag
// alone cannot authorize mutation: private pipes, B0 proof and run scope follow.
func TestMain(m *testing.M) {
	if os.Getenv("ASEP_RF09_RECEIVER") == "true" {
		os.Exit(runInboxController())
	}
	if os.Getenv("ASEP_RF07B_CHILD") == "true" {
		os.Exit(runCancellationLeaf())
	}
	if os.Getenv("ASEP_RF07B_CONTROLLER") == "true" {
		os.Exit(runFinalizationController())
	}
	if os.Getenv(resultControllerFlag) == "true" {
		os.Exit(runResultController())
	}
	os.Exit(m.Run())
}

func runResultController() int {
	input, err := os.Stdin.Stat()
	if err != nil || input.Mode()&os.ModeNamedPipe == 0 {
		return 2
	}
	checkpoint := os.NewFile(3, "private-result-commit-checkpoint")
	if checkpoint == nil {
		return 2
	}
	defer checkpoint.Close()
	info, err := checkpoint.Stat()
	if err != nil || info.Mode()&os.ModeNamedPipe == 0 {
		return 2
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	stopIO := context.AfterFunc(ctx, func() {
		_ = os.Stdin.Close()
		_ = checkpoint.Close()
		_ = os.Stdout.Close()
	})
	defer stopIO()
	decoder := json.NewDecoder(io.LimitReader(os.Stdin, 16*1024))
	decoder.DisallowUnknownFields()
	var config resultControllerConfig
	if decoder.Decode(&config) != nil || config.Command.validate() != nil ||
		!strings.HasPrefix(config.Command.EvidenceRef, config.Prefix+":") {
		return 2
	}
	// The pipe carries only the disposable runtime credential, never admin or
	// Docker capabilities. Connect verifies the actual database/role/nonce.
	if os.Setenv("ASEP_VN_T10_DISPOSABLE_DB", "true") != nil ||
		os.Setenv("ASEP_VN_T10_TEST_PREFIX", config.Prefix) != nil {
		return 2
	}
	db, err := testsupport.Connect(ctx, config.DatabaseURL, config.Nonce)
	if err != nil {
		return 3 // Distinguish proof rejection without printing driver errors.
	}
	defer db.Close()
	store := &Store{db: db, databaseURL: config.DatabaseURL}
	if _, err := store.SyntheticRunStatus(ctx, config.Command.Lease.RunID, config.Prefix); err != nil {
		return 2
	}
	receipt, err := store.advanceInvestigation(ctx, config.Command)
	if err != nil {
		return 2
	}
	// The existing method has returned a durable receipt. This independent
	// test-control channel is NOT the application ACK and contains no receipt.
	if _, err := checkpoint.Write([]byte{1}); err != nil {
		return 2
	}
	_ = checkpoint.Close()
	var release string
	if decoder.Decode(&release) != nil || release != "emit-ack" {
		return 2
	}
	if json.NewEncoder(os.Stdout).Encode(receipt) != nil {
		return 2
	}
	return 0
}

type resultACK struct {
	body []byte
	err  error
}

type resultController struct {
	command    *exec.Cmd
	input      *os.File
	checkpoint <-chan error
	ack        <-chan resultACK
	done       chan struct{}
	waitErr    error // Read only after done closes.
}

func resultProcessPipe(t *testing.T) (*os.File, *os.File) {
	t.Helper()
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal("private result-controller pipe unavailable")
	}
	t.Cleanup(func() { _ = reader.Close(); _ = writer.Close() })
	return reader, writer
}

func startResultController(t *testing.T, config resultControllerConfig) *resultController {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	input, control := resultProcessPipe(t)
	checkpoint, checkpointWriter := resultProcessPipe(t)
	ack, ackWriter := resultProcessPipe(t)
	command := exec.CommandContext(ctx, os.Args[0], "-test.run=^$")
	// Exercise wire timestamps independently of the parent host's timezone.
	command.Env = append(testsupport.Environment(), resultControllerFlag+"=true", "TZ=UTC")
	command.Stdin, command.Stdout, command.Stderr = input, ackWriter, io.Discard
	command.ExtraFiles = []*os.File{checkpointWriter}
	command.WaitDelay = time.Second
	// Inherit the runner's process group so its bounded cleanup also covers us.
	if err := command.Start(); err != nil {
		t.Fatal("trusted result controller could not start")
	}
	_ = input.Close()
	_ = checkpointWriter.Close()
	_ = ackWriter.Close()
	committed := make(chan error, 1)
	acknowledged := make(chan resultACK, 1)
	child := &resultController{command: command, input: control, checkpoint: committed,
		ack: acknowledged, done: make(chan struct{})}
	t.Cleanup(func() {
		_ = command.Process.Kill()
		cancel()
		_ = control.Close()
		select {
		case <-child.done:
		case <-time.After(4 * time.Second):
			t.Error("owned result controller was not reaped within deadline")
		}
	})
	go func() { child.waitErr = command.Wait(); close(child.done) }()
	go func() {
		var marker [1]byte
		_, err := io.ReadFull(checkpoint, marker[:])
		if err == nil && marker[0] != 1 {
			err = errors.New("unexpected result checkpoint")
		}
		committed <- err
	}()
	go func() {
		// Owned pipes, rather than StdoutPipe, prevent Wait from discarding a
		// premature ACK before this reader observes it. Never log these bytes.
		body, err := io.ReadAll(io.LimitReader(ack, 16*1024+1))
		acknowledged <- resultACK{body, err}
	}()
	if err := json.NewEncoder(control).Encode(config); err != nil {
		t.Fatal("private result-controller configuration handoff failed")
	}
	return child
}

func awaitResultControllerExit(t *testing.T, child *resultController) {
	t.Helper()
	select {
	case <-child.done:
	case <-testContext(t).Done():
		t.Fatal("result controller did not exit within deadline")
	}
}

func awaitResultACK(t *testing.T, child *resultController) []byte {
	t.Helper()
	select {
	case ack := <-child.ack:
		if ack.err != nil || len(ack.body) > 16*1024 {
			t.Fatal("private application ACK could not be read safely")
		}
		return ack.body
	case <-testContext(t).Done():
		t.Fatal("application ACK pipe deadline exceeded")
		return nil
	}
}

func TestResultProcessCrashAfterCommitBeforeACK(t *testing.T) {
	for _, scenario := range []string{"ack-after-release", "crash-before-ack", "invalid-disposable-proof"} {
		t.Run(scenario, func(t *testing.T) {
			store := storeForTest(t)
			admission, run := admitForTest(t, store, "result-process-crash")
			lease, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-result-controller", 30)
			if err != nil {
				t.Fatal("result process fixture claim failed")
			}
			command := validAdvance(t, admission, lease) // Generated once, before launch.
			before := snapshotFor(t, store, run)
			config := resultControllerConfig{testsupport.DatabaseURL(t), os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"),
				os.Getenv("ASEP_VN_T10_TEST_PREFIX"), command}
			if scenario == "invalid-disposable-proof" {
				if config.Nonce[0] == '0' {
					config.Nonce = "1" + config.Nonce[1:]
				} else {
					config.Nonce = "0" + config.Nonce[1:]
				}
			}
			child := startResultController(t, config)
			if scenario == "invalid-disposable-proof" {
				awaitResultControllerExit(t, child)
				var exit *exec.ExitError
				if !errors.As(child.waitErr, &exit) || exit.ExitCode() != 3 || len(awaitResultACK(t, child)) != 0 {
					t.Fatal("controller did not reject invalid B0 proof before mutation")
				}
				select {
				case err := <-child.checkpoint:
					if err == nil || snapshotFor(t, store, run) != before {
						t.Fatal("invalid proof reached commit checkpoint or changed persisted state")
					}
				case <-testContext(t).Done():
					t.Fatal("invalid-proof checkpoint pipe deadline exceeded")
				}
				t.Log("RF-06 safety: controller rejected wrong disposable nonce; no checkpoint/ACK or persisted mutation")
				return
			}
			select {
			case err := <-child.checkpoint:
				if err != nil {
					t.Fatal("controller did not reach the post-COMMIT checkpoint")
				}
			case <-testContext(t).Done():
				t.Fatal("post-COMMIT checkpoint deadline exceeded")
			}
			// A separate guarded pool independently proves the receipt is durable
			// while the child is still gated before its application ACK.
			observer := storeForTest(t)
			digest, err := command.canonicalPayloadSHA256()
			if err != nil {
				t.Fatal("expected result digest unavailable")
			}
			original, err := queryResult(testContext(t), observer.db, command.OperationID, digest)
			if err != nil || original.ClaimantID != lease.ClaimantID || original.LeaseEpoch != lease.LeaseEpoch ||
				original.ExpectedRunVersion != command.ExpectedRunVersion || original.PolicyEpoch != command.ExpectedPolicyEpoch ||
				original.SourceFingerprintSHA256 != command.ExpectedSourceFingerprintSHA256 {
				t.Fatal("independent observer could not confirm the original bound receipt")
			}
			checkAdvanced(t, observer, run, original)
			accepted := snapshotFor(t, observer, run)
			if accepted.firstTaskClaimant != before.firstTaskClaimant || accepted.firstTaskEpoch != before.firstTaskEpoch ||
				accepted.firstTaskDeliveries != before.firstTaskDeliveries || accepted.firstTaskExpiry != before.firstTaskExpiry ||
				accepted.limit != before.limit {
				t.Fatal("result completion changed lease or budget-limit accounting")
			}
			select {
			case <-child.ack:
				t.Fatal("application ACK stream ended before checkpoint release or controlled crash")
			default:
			}
			if scenario == "ack-after-release" {
				if err := json.NewEncoder(child.input).Encode("emit-ack"); err != nil {
					t.Fatal("application ACK gate release failed")
				}
				var acknowledgement advanceReceipt
				if json.Unmarshal(awaitResultACK(t, child), &acknowledgement) != nil || !acknowledgement.AcceptedAt.Equal(original.AcceptedAt) {
					t.Fatal("released application ACK did not contain the independently observed receipt")
				}
				comparison := acknowledgement
				comparison.AcceptedAt = original.AcceptedAt
				if comparison != original {
					t.Fatal("released application ACK changed the independently observed receipt identity")
				}
				awaitResultControllerExit(t, child)
				if child.waitErr != nil || snapshotFor(t, observer, run) != accepted {
					t.Fatal("positive checkpoint control did not finish cleanly")
				}
				t.Log("RF-06 positive control: confirmed durable receipt; application ACK emitted only after explicit release")
				return
			}
			if err := child.command.Process.Kill(); err != nil {
				t.Fatal("owned result controller could not be terminated at the checkpoint")
			}
			awaitResultControllerExit(t, child)
			var exit *exec.ExitError
			if !errors.As(child.waitErr, &exit) {
				t.Fatal("controlled result process did not record hard termination")
			}
			status, ok := exit.Sys().(syscall.WaitStatus)
			if !ok || !status.Signaled() || status.Signal() != syscall.SIGKILL || len(awaitResultACK(t, child)) != 0 {
				t.Fatal("expected reaped SIGKILL and zero application ACK bytes")
			}
			reconnect := storeForTest(t)
			recovered, err := reconnect.reconcileInvestigation(testContext(t), command)
			if err != nil || recovered != original {
				t.Fatal("reconnect did not recover every original receipt field")
			}
			replayed, err := reconnect.advanceInvestigation(testContext(t), command)
			if err != nil || replayed != original || snapshotFor(t, reconnect, run) != accepted {
				t.Fatal("post-crash replay changed receipt identity or duplicated durable progress")
			}
			checkAdvanced(t, reconnect, run, recovered)
			t.Logf("RF-06 observed: independent durable receipt before SIGKILL; child reaped; ACK bytes=0; exact reconcile/replay receipt; version=%d tasks=%d attempts=%d transitions=%d outbox=%d receipts=%d delivery=%d semantic_attempt=%d budget=%d",
				accepted.version, accepted.tasks, accepted.attempts, accepted.transitions, accepted.outbox,
				accepted.receipts, accepted.firstTaskDeliveries, accepted.firstAttemptNo, accepted.used)
		})
	}
}
