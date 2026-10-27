//go:build integration

package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

// Fixed synthetic leaf: no tool execution, process spawning, DB or API token.
// Its complete process inventory is exactly this child, never arbitrary code.
func runCancellationLeaf() int {
	info, err := os.Stdin.Stat()
	if err != nil || info.Mode()&os.ModeNamedPipe == 0 {
		return 2
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	stop := context.AfterFunc(ctx, func() { _ = os.Stdin.Close() })
	defer stop()
	d := json.NewDecoder(io.LimitReader(os.Stdin, 4096))
	var resistant bool
	if d.Decode(&resistant) != nil {
		return 2
	}
	if resistant {
		signal.Ignore(syscall.SIGTERM)
	}
	if json.NewEncoder(os.Stdout).Encode("ready") != nil {
		return 2
	}
	var start string
	if d.Decode(&start) != nil || start != "start" {
		return 2
	}
	if json.NewEncoder(os.Stdout).Encode("started") != nil {
		return 2
	}
	_, _ = io.Copy(io.Discard, os.Stdin)
	return 0
}

type ownedCancellationScope struct {
	registration                    executionScope
	command                         *exec.Cmd
	input                           *os.File
	output                          *json.Decoder
	done                            chan struct{}
	waitErr                         error
	waitFault                       error // Test-only invalid evidence, injected after the actual Wait/reap.
	exitErr                         error // A failed exit validation cannot become proof on retry.
	exitVerified                    bool
	workspace                       string
	closed, started, cleanupBlocked bool
	outcome                         string
	status                          int
	observation                     *cleanupObservation
}

// The test parent is the surviving trusted keeper. Controllers may crash, but
// only this parent owns the original handles, private gates and workspace paths.
type cancellationKeeper struct {
	t         *testing.T
	store     *Store
	inventory executionInventory
	mu        sync.Mutex
	scopes    map[string]*ownedCancellationScope
}

func currentKeeperIdentity(t *testing.T, id string) keeperIdentity {
	t.Helper()
	boot, err := os.ReadFile("/proc/sys/kernel/random/boot_id")
	if err != nil {
		t.Fatal("keeper boot identity unavailable")
	}
	stat, err := os.ReadFile("/proc/self/stat")
	if err != nil {
		t.Fatal("keeper process identity unavailable")
	}
	fields := strings.Fields(string(stat)[strings.LastIndex(string(stat), ")")+1:])
	if len(fields) <= 19 {
		t.Fatal("keeper start identity unavailable")
	}
	start, err := strconv.ParseInt(fields[19], 10, 64)
	if err != nil {
		t.Fatal("keeper start identity invalid")
	}
	return keeperIdentity{ID: id, BootID: strings.TrimSpace(string(boot)), PID: os.Getpid(), Start: start}
}

func newCancellationKeeper(t *testing.T, s *Store, run AdmissionReceipt) *cancellationKeeper {
	t.Helper()
	inventory, err := readExecutionInventory(testContext(t), s.db, run.RunID)
	if err != nil || currentKeeperIdentity(t, inventory.Keeper.ID) != inventory.Keeper {
		t.Fatal("new admitted keeper inventory unavailable")
	}
	k := &cancellationKeeper{t: t, store: s, inventory: executionInventory{RunID: run.RunID,
		SyntheticPrefix: inventory.SyntheticPrefix, Keeper: inventory.Keeper}, scopes: map[string]*ownedCancellationScope{}}
	t.Cleanup(func() {
		for _, scope := range k.scopes {
			if scope.command != nil {
				_ = scope.command.Process.Kill()
				_ = scope.input.Close()
				select {
				case <-scope.done:
				case <-time.After(3 * time.Second):
					t.Error("owned cancellation leaf was not reaped")
				}
			}
			if scope.workspace != "" {
				_ = os.RemoveAll(scope.workspace)
			}
		}
	})
	return k
}

func (k *cancellationKeeper) register() *ownedCancellationScope {
	k.t.Helper()
	k.mu.Lock()
	defer k.mu.Unlock()
	id, err := freshUUID()
	if err != nil {
		k.t.Fatal("execution identity unavailable")
	}
	c := executionScope{ExecutionID: id, RunID: k.inventory.RunID, Keeper: k.inventory.Keeper}
	if err := k.store.registerExecution(testContext(k.t), c); err != nil {
		k.t.Fatal("execution registration failed")
	}
	scope := &ownedCancellationScope{registration: c}
	k.scopes[id] = scope
	return scope
}

func (k *cancellationKeeper) spawn(scope *ownedCancellationScope, resistant bool) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.scopes[scope.registration.ExecutionID] != scope || scope.closed || scope.command != nil {
		return errCancellationRejected
	}
	// No execution may be spawned without a confirmed registration owned here.
	registered, err := readExecutionScope(testContext(k.t), k.store.db, scope.registration.ExecutionID)
	if err != nil || registered != scope.registration {
		return errCancellationRejected
	}
	var active bool
	err = k.store.db.QueryRowContext(testContext(k.t), `SELECT state IN ('INVESTIGATING','PLANNING')
        FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, scope.registration.RunID).Scan(&active)
	if err != nil {
		return err
	}
	if !active {
		return errCancellationRejected
	}
	scope.workspace, err = os.MkdirTemp(k.t.TempDir(), "owned-scope-")
	if err != nil {
		return err
	}
	input, control := resultProcessPipe(k.t)
	output, writer := resultProcessPipe(k.t)
	cmd := exec.CommandContext(k.t.Context(), os.Args[0], "-test.run=^$")
	cmd.Env = []string{"ASEP_RF07B_CHILD=true"}
	cmd.Dir = scope.workspace
	cmd.Stdin, cmd.Stdout, cmd.Stderr = input, writer, io.Discard
	cmd.WaitDelay = time.Second // Keep the runner process group; never broad PID kill.
	if err := cmd.Start(); err != nil {
		return err
	}
	_ = input.Close()
	_ = writer.Close()
	scope.command, scope.input, scope.output, scope.done = cmd, control, json.NewDecoder(output), make(chan struct{})
	go func() {
		scope.waitErr = cmd.Wait()
		if scope.waitFault != nil {
			scope.waitErr = scope.waitFault
		}
		close(scope.done)
	}()
	if err := json.NewEncoder(control).Encode(resistant); err != nil {
		return err
	}
	ready := make(chan error, 1)
	go func() {
		var value string
		err := scope.output.Decode(&value)
		if err == nil && value != "ready" {
			err = errors.New("unexpected startup marker")
		}
		ready <- err
	}()
	select {
	case err := <-ready:
		return err
	case <-testContext(k.t).Done():
		return errors.New("leaf readiness deadline")
	}
}

func (k *cancellationKeeper) release(scope *ownedCancellationScope, b executionBinding) error {
	if err := k.store.bindExecution(testContext(k.t), b); err != nil {
		return err
	}
	k.mu.Lock()
	defer k.mu.Unlock()
	if scope.closed || scope.command == nil || scope.started || b.Scope != scope.registration {
		return errCancellationRejected
	}
	// The DB check and IPC below are separate: concurrent cancellation may
	// commit before the child receives "start". This is not an atomic production
	// startup fence; cleanup must still close/stop every scope before proof.
	var active bool
	err := k.store.db.QueryRowContext(testContext(k.t), `SELECT r.state=t.stage AND r.run_version=$3
        AND t.status='LEASED' AND t.lease_epoch=$4 AND t.lease_expires_at>clock_timestamp()
        FROM orchestration.workflow_runs r JOIN orchestration.task_deliveries t USING(run_id)
        WHERE r.run_id=$1::uuid AND t.task_id=$2::uuid`, b.Task.RunID, b.Task.TaskID, b.RunVersion, b.Task.LeaseEpoch).Scan(&active)
	if err != nil {
		return err
	}
	if !active {
		return errCancellationRejected
	}
	if err := json.NewEncoder(scope.input).Encode("start"); err != nil {
		return err
	}
	ack := make(chan error, 1)
	go func() {
		var value string
		err := scope.output.Decode(&value)
		if err == nil && value != "started" {
			err = errors.New("unexpected execution marker")
		}
		ack <- err
	}()
	select {
	case err := <-ack:
		if err != nil {
			return err
		}
		scope.started = true
		return nil
	case <-testContext(k.t).Done():
		return errors.New("leaf startup deadline")
	}
}

func (k *cancellationKeeper) stop(scope executionScope, request cancellationReceipt, grace time.Duration) (cleanupObservation, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	owned := k.scopes[scope.ExecutionID]
	if owned == nil || owned.registration != scope || scope.Keeper != k.inventory.Keeper ||
		currentKeeperIdentity(k.t, k.inventory.Keeper.ID) != k.inventory.Keeper || request.RunID != scope.RunID {
		return cleanupObservation{}, errCancellationRejected
	}
	// Independently confirm durable intent before gate closure, signal, cleanup
	// or cached proof replay. These are bounded autocommit reads; no database
	// transaction/row lock is retained across any process action below.
	ctx := testContext(k.t)
	persisted, err := queryCancellation(ctx, k.store.db, request.OperationID, request.PayloadSHA256)
	if err != nil {
		return cleanupObservation{}, err
	}
	if persisted != request {
		return cleanupObservation{}, errCancellationRejected
	}
	inventory, err := readExecutionInventory(ctx, k.store.db, scope.RunID)
	if err != nil {
		return cleanupObservation{}, err
	}
	if inventory != k.inventory {
		return cleanupObservation{}, errCancellationRejected
	}
	var frozen, state string
	var count int
	var version int64
	err = k.store.db.QueryRowContext(ctx, `SELECT f.manifest_sha256,f.scope_count,r.state,r.run_version
        FROM orchestration.workflow_cancellation_inventories f
        JOIN orchestration.workflow_runs r USING(run_id)
        JOIN orchestration.workflow_execution_scopes e USING(run_id,keeper_id)
        WHERE f.request_transition_id=$1::uuid AND f.run_id=$2::uuid AND f.keeper_id=$3::uuid
          AND f.request_kind='CANCEL_REQUEST' AND e.execution_id=$4::uuid`,
		request.TransitionID, scope.RunID, scope.Keeper.ID, scope.ExecutionID).Scan(&frozen, &count, &state, &version)
	if err != nil {
		return cleanupObservation{}, err
	}
	manifest, size, err := executionManifest(ctx, k.store.db, inventory)
	if err != nil {
		return cleanupObservation{}, err
	}
	if manifest != frozen || size != count || (owned.observation == nil && (state != "CANCEL_REQUESTED" || version != request.RunVersion)) {
		return cleanupObservation{}, errCancellationRejected
	}
	if owned.observation != nil {
		if owned.observation.RequestTransitionID != request.TransitionID {
			return cleanupObservation{}, errCancellationConflict
		}
		return *owned.observation, nil
	}
	owned.closed = true // Permanently closes startup; release/stop share this lock.
	if owned.exitErr != nil {
		return cleanupObservation{}, owned.exitErr
	}
	if !owned.exitVerified {
		outcome, waitStatus := "NOT_STARTED", 0
		if owned.command != nil {
			select {
			case <-owned.done:
				outcome = "EXITED"
			default:
				if err := owned.command.Process.Signal(syscall.SIGTERM); err != nil && !errors.Is(err, os.ErrProcessDone) {
					return cleanupObservation{}, err
				}
				timer := time.NewTimer(grace)
				select {
				case <-owned.done:
					outcome = "TERM"
				case <-timer.C:
					if err := owned.command.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
						return cleanupObservation{}, err
					}
					select {
					case <-owned.done:
						outcome = "KILL"
					case <-testContext(k.t).Done():
						return cleanupObservation{}, errors.New("reap deadline")
					}
				}
				timer.Stop()
			}
			_ = owned.input.Close()
			if owned.waitErr != nil {
				var exit *exec.ExitError
				if !errors.As(owned.waitErr, &exit) {
					owned.exitErr = errors.New("exit observation unavailable")
					return cleanupObservation{}, owned.exitErr
				}
				status, ok := exit.Sys().(syscall.WaitStatus)
				if !ok {
					owned.exitErr = errors.New("wait status unavailable")
					return cleanupObservation{}, owned.exitErr
				}
				waitStatus = int(status)
				if outcome == "KILL" && (!status.Signaled() || status.Signal() != syscall.SIGKILL) {
					owned.exitErr = errors.New("SIGKILL was not observed")
					return cleanupObservation{}, owned.exitErr
				}
				if outcome == "TERM" && (!status.Signaled() || status.Signal() != syscall.SIGTERM) {
					owned.exitErr = errors.New("SIGTERM was not observed")
					return cleanupObservation{}, owned.exitErr
				}
			} else {
				outcome = "EXITED"
			}
		}
		// Cache only verified exit/reap evidence, so a workspace-cleanup retry
		// retains the original outcome without trusting an attempted signal.
		owned.outcome, owned.status, owned.exitVerified = outcome, waitStatus, true
	}
	if owned.cleanupBlocked {
		return cleanupObservation{}, errors.New("controlled workspace cleanup withheld")
	}
	if owned.workspace != "" {
		if err := os.RemoveAll(owned.workspace); err != nil {
			return cleanupObservation{}, err
		}
		if _, err := os.Lstat(owned.workspace); !errors.Is(err, os.ErrNotExist) {
			return cleanupObservation{}, errors.New("workspace cleanup not observed")
		}
	}
	id, err := freshUUID()
	if err != nil {
		return cleanupObservation{}, err
	}
	c := cleanupObservation{ObservationID: id, RequestTransitionID: request.TransitionID, Scope: scope,
		Outcome: owned.outcome, WaitStatus: owned.status, ScopeClosed: true, Reaped: owned.command != nil, WorkspaceClean: true}
	owned.observation = &c
	return c, nil
}

type finalizationControllerConfig struct {
	DatabaseURL, Nonce, Prefix, Mode string
	Observations                     []cleanupObservation
	Finalization                     cancellationFinalization
}

func runFinalizationController() int {
	info, err := os.Stdin.Stat()
	if err != nil || info.Mode()&os.ModeNamedPipe == 0 {
		return 2
	}
	marker, ack := os.NewFile(3, "finalization-checkpoint"), os.NewFile(4, "finalization-ack")
	if marker == nil || ack == nil {
		return 2
	}
	defer marker.Close()
	defer ack.Close()
	for _, f := range []*os.File{marker, ack} {
		info, err := f.Stat()
		if err != nil || info.Mode()&os.ModeNamedPipe == 0 {
			return 2
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	stop := context.AfterFunc(ctx, func() { _ = os.Stdin.Close(); _ = marker.Close(); _ = ack.Close() })
	defer stop()
	d := json.NewDecoder(io.LimitReader(os.Stdin, 32*1024))
	d.DisallowUnknownFields()
	var c finalizationControllerConfig
	if d.Decode(&c) != nil || c.Finalization.validate() != nil {
		return 2
	}
	if os.Setenv("ASEP_VN_T10_DISPOSABLE_DB", "true") != nil || os.Setenv("ASEP_VN_T10_TEST_PREFIX", c.Prefix) != nil {
		return 2
	}
	db, err := testsupport.Connect(ctx, c.DatabaseURL, c.Nonce)
	if err != nil {
		return 3
	}
	defer db.Close()
	s := &Store{db: db, databaseURL: c.DatabaseURL}
	if c.Mode == "before-evidence" {
		if _, err := marker.Write([]byte{'B'}); err != nil {
			return 2
		}
		_ = marker.Close()
		var release string
		if d.Decode(&release) != nil || release != "continue" {
			return 2
		}
	}
	for _, o := range c.Observations {
		if s.recordExecutionCleanup(ctx, o) != nil {
			return 2
		}
	}
	r, err := s.finalizeCancellation(ctx, c.Finalization)
	if err != nil {
		return 2
	}
	if c.Mode != "before-evidence" {
		if _, err := marker.Write([]byte{'F'}); err != nil {
			return 2
		}
		_ = marker.Close()
	}
	var release string
	if d.Decode(&release) != nil || release != "ack" {
		return 2
	}
	if json.NewEncoder(ack).Encode(r) != nil {
		return 2
	}
	return 0
}

func startFinalizationController(t *testing.T, c finalizationControllerConfig) *resultController {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
	t.Cleanup(cancel)
	input, control := resultProcessPipe(t)
	checkpoint, marker := resultProcessPipe(t)
	output, ack := resultProcessPipe(t)
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^$")
	// Exercise wire timestamps independently of the parent host's timezone.
	cmd.Env = append(testsupport.Environment(), "ASEP_RF07B_CONTROLLER=true", "TZ=UTC")
	cmd.Stdin, cmd.Stdout, cmd.Stderr = input, io.Discard, io.Discard
	cmd.ExtraFiles = []*os.File{marker, ack}
	cmd.WaitDelay = time.Second
	if cmd.Start() != nil {
		t.Fatal("trusted finalization controller unavailable")
	}
	_ = input.Close()
	_ = marker.Close()
	_ = ack.Close()
	checkpointResult := make(chan error, 1)
	ackResult := make(chan resultACK, 1)
	child := &resultController{command: cmd, input: control, checkpoint: checkpointResult, ack: ackResult, done: make(chan struct{})}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		cancel()
		_ = control.Close()
		select {
		case <-child.done:
		case <-time.After(3 * time.Second):
			t.Error("finalization controller not reaped")
		}
	})
	go func() { child.waitErr = cmd.Wait(); close(child.done) }()
	go func() {
		var b [1]byte
		_, err := io.ReadFull(checkpoint, b[:])
		want := byte('F')
		if c.Mode == "before-evidence" {
			want = 'B'
		}
		if err == nil && b[0] != want {
			err = errors.New("unexpected finalization checkpoint")
		}
		checkpointResult <- err
	}()
	go func() { b, err := io.ReadAll(io.LimitReader(output, 16*1024+1)); ackResult <- resultACK{b, err} }()
	if json.NewEncoder(control).Encode(c) != nil {
		t.Fatal("private controller configuration failed")
	}
	return child
}

func awaitFinalizationCheckpoint(t *testing.T, c *resultController) {
	t.Helper()
	select {
	case err := <-c.checkpoint:
		if err != nil {
			t.Fatal("finalization checkpoint not reached")
		}
	case <-testContext(t).Done():
		t.Fatal("finalization checkpoint deadline")
	}
}

func killFinalizationController(t *testing.T, c *resultController) {
	t.Helper()
	if c.command.Process.Kill() != nil {
		t.Fatal("controlled finalization process could not be killed")
	}
	awaitResultControllerExit(t, c)
	var exit *exec.ExitError
	if !errors.As(c.waitErr, &exit) {
		t.Fatal("controller did not record hard termination")
	}
	status, ok := exit.Sys().(syscall.WaitStatus)
	if !ok || !status.Signaled() || status.Signal() != syscall.SIGKILL || len(awaitResultACK(t, c)) != 0 {
		t.Fatal("controller was not reaped with SIGKILL and zero ACK")
	}
}
