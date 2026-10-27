//go:build integration

package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

func keeperRun(t *testing.T) (*Store, Admission, AdmissionReceipt, *cancellationKeeper) {
	t.Helper()
	s := storeForTest(t)
	a := admissionForTest(t, "rf07b")
	id, err := freshUUID()
	if err != nil {
		t.Fatal("keeper identity unavailable")
	}
	run, err := s.admitExecution(testContext(t), a, currentKeeperIdentity(t, id))
	if err != nil {
		t.Fatal("atomic execution admission failed")
	}
	return s, a, run, newCancellationKeeper(t, s, run)
}

func keeperLease(t *testing.T, s *Store, k *cancellationKeeper, scope *ownedCancellationScope, seconds int) TaskLease {
	t.Helper()
	lease, err := s.ClaimForRun(testContext(t), k.inventory.RunID, "synthetic-rf07b", seconds)
	if err != nil {
		t.Fatal("RF-07B claim failed")
	}
	if err := k.release(scope, executionBinding{Scope: scope.registration, Task: lease, RunVersion: 1}); err != nil {
		t.Fatal("durable binding/startup failed")
	}
	return lease
}

func keeperCancellation(t *testing.T, s *Store, a Admission, task TaskLease, version int64) (cancellationRequest, cancellationReceipt) {
	t.Helper()
	c := cancellationForTest(t, a, task, version)
	r, err := s.requestCancellation(testContext(t), c)
	if err != nil {
		t.Fatal("RF-07B cancellation request failed")
	}
	return c, r
}

func finalizationForTest(t *testing.T, s *Store, c cancellationRequest, r cancellationReceipt, observations []cleanupObservation) cancellationFinalization {
	t.Helper()
	var inventory string
	if err := s.db.QueryRowContext(testContext(t), `SELECT manifest_sha256 FROM orchestration.workflow_cancellation_inventories
        WHERE request_transition_id=$1::uuid`, r.TransitionID).Scan(&inventory); err != nil {
		t.Fatal("frozen inventory unavailable")
	}
	digest, err := cleanupSetDigest(r.TransitionID, inventory, observations)
	if err != nil {
		t.Fatal("cleanup set digest unavailable")
	}
	id, err := freshUUID()
	if err != nil {
		t.Fatal("finalization identity unavailable")
	}
	return cancellationFinalization{OperationID: id, Request: c, ExpectedRunVersion: r.RunVersion, InventorySHA256: inventory, CleanupSHA256: digest}
}

func keeperProof(t *testing.T, k *cancellationKeeper, scope *ownedCancellationScope, r cancellationReceipt) cleanupObservation {
	t.Helper()
	proof, err := k.stop(scope.registration, r, 100*time.Millisecond)
	if err != nil {
		t.Fatal("owned termination/reap/cleanup not proven")
	}
	return proof
}

func persistProof(t *testing.T, s *Store, p cleanupObservation) {
	t.Helper()
	if s.recordExecutionCleanup(testContext(t), p) != nil {
		t.Fatal("immutable cleanup evidence not persisted")
	}
}

func checkFinalized(t *testing.T, s *Store, run AdmissionReceipt, before transitionSnapshot, c cancellationFinalization, r finalizationReceipt) {
	t.Helper()
	want := before
	want.state = "CANCELLED"
	want.version++
	want.transitions++
	want.outstanding--
	want.firstTaskStatus = "CANCELLED"
	want.firstAttemptState = "CANCELLED"
	if snapshotFor(t, s, run) != want {
		t.Fatal("finalization lost atomic state/task/attempt/audit or changed accounting/history")
	}
	digest, err := c.digest()
	if err != nil {
		t.Fatal("finalization digest unavailable")
	}
	durable, err := queryFinalization(testContext(t), s.db, c.OperationID, digest)
	if err != nil || durable != r || r.Control.RunVersion != c.ExpectedRunVersion+1 ||
		r.InventorySHA256 != c.InventorySHA256 || r.CleanupSHA256 != c.CleanupSHA256 || r.Control.RequestedAt.IsZero() {
		t.Fatal("finalization receipt identity/evidence mismatch")
	}
}

func TestCancellationFinalizationProcesses(t *testing.T) {
	for _, mode := range []string{"TERM", "KILL"} {
		t.Run(mode, func(t *testing.T) {
			s, a, run, k := keeperRun(t)
			scope := k.register()
			if k.spawn(scope, mode == "KILL") != nil {
				t.Fatal("owned synthetic leaf unavailable")
			}
			lease := keeperLease(t, s, k, scope, 60)
			c, request := keeperCancellation(t, s, a, lease, 1)
			before := snapshotFor(t, s, run)
			missing := finalizationForTest(t, s, c, request, nil)
			if _, err := s.finalizeCancellation(testContext(t), missing); !errors.Is(err, errCancellationRejected) {
				t.Fatal("missing cleanup finalized")
			}
			proof := keeperProof(t, k, scope, request)
			if proof.Outcome != mode || !proof.Reaped || !scope.closed {
				t.Fatal("actual termination mode or reap was not observed")
			}
			persistProof(t, s, proof)
			f := finalizationForTest(t, s, c, request, []cleanupObservation{proof})
			r, err := s.finalizeCancellation(testContext(t), f)
			if err != nil {
				t.Fatal("proven cancellation did not finalize")
			}
			checkFinalized(t, s, run, before, f, r)
			accepted := snapshotFor(t, s, run)
			observer := storeForTest(t)
			got, err := observer.reconcileFinalization(testContext(t), f)
			if err != nil || got != r {
				t.Fatal("independent finalization recovery failed")
			}
			got, err = observer.finalizeCancellation(testContext(t), f)
			if err != nil || got != r || observer.recordExecutionCleanup(testContext(t), proof) != nil {
				t.Fatal("exact finalization/cleanup replay failed")
			}
			old, err := observer.requestCancellation(testContext(t), c)
			if err != nil || old != request || snapshotFor(t, s, run) != accepted {
				t.Fatal("request replay after finalization changed original identity/history")
			}
			cached, err := k.stop(scope.registration, request, time.Millisecond)
			if err != nil || cached != proof {
				t.Fatal("validated cleanup replay after finalization changed original proof")
			}
			if k.release(scope, executionBinding{Scope: scope.registration, Task: lease, RunVersion: 1}) == nil {
				t.Fatal("historical binding reopened closed scope")
			}
			if _, err := s.Heartbeat(testContext(t), lease, 60); !errors.Is(err, ErrLeaseLost) {
				t.Fatal("finalized task regained lease")
			}
			if _, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-late", 60); !errors.Is(err, ErrNoTask) {
				t.Fatal("finalized run became claimable")
			}
			t.Logf("RF-07B observed %s, reap and owned workspace removal; CANCELLED version=3; one terminal audit; epoch/delivery/semantic-attempt/budget retained", mode)
		})
	}
}

func TestExecutionCancellationStartup(t *testing.T) {
	for _, winner := range []string{"registration", "cancellation", "bound-startup"} {
		t.Run(winner, func(t *testing.T) {
			s, a, run, k := keeperRun(t)
			queued := TaskLease{RunID: run.RunID, TaskID: run.TaskID, AttemptID: run.AttemptID, Stage: "INVESTIGATING"}
			if winner == "bound-startup" {
				scope := k.register()
				if k.spawn(scope, false) != nil {
					t.Fatal("gated leaf unavailable")
				}
				lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-startup", 60)
				if err != nil {
					t.Fatal("startup claim unavailable")
				}
				binding := executionBinding{Scope: scope.registration, Task: lease, RunVersion: 1}
				if s.bindExecution(testContext(t), binding) != nil {
					t.Fatal("startup binding unavailable")
				}
				c, r := keeperCancellation(t, s, a, lease, 1)
				if k.release(scope, binding) == nil || scope.started {
					t.Fatal("cancellation allowed queued startup")
				}
				p := keeperProof(t, k, scope, r)
				persistProof(t, s, p)
				f := finalizationForTest(t, s, c, r, []cleanupObservation{p})
				before := snapshotFor(t, s, run)
				got, err := s.finalizeCancellation(testContext(t), f)
				if err != nil {
					t.Fatal("gated scope could not finalize after cleanup")
				}
				checkFinalized(t, s, run, before, f, got)
				return
			}
			id, err := freshUUID()
			if err != nil {
				t.Fatal("registration identity unavailable")
			}
			scope := &ownedCancellationScope{registration: executionScope{ExecutionID: id, RunID: run.RunID, Keeper: k.inventory.Keeper}}
			k.scopes[id] = scope // Reserved locally, before any durable write or spawn.
			c := cancellationForTest(t, a, queued, 1)
			reg, regPID := heartbeatProbe(t)
			reg.databaseURL = s.databaseURL
			cancel, cancelPID := heartbeatProbe(t)
			cancel.databaseURL = s.databaseURL
			regReply := make(chan error, 1)
			regCtx := testContext(t)
			var requestReply <-chan cancellationReply
			if winner == "registration" {
				// Queue the registration lock before cancellation on the existing
				// mutable run row; ownership tables deliberately have no UPDATE grant.
				gate, pid := holdHeartbeatRow(t, s, `SELECT run_id::text FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, run.RunID)
				go func() { regReply <- reg.registerExecution(regCtx, scope.registration) }()
				waitHeartbeatBlockedBy(t, s, regPID, pid)
				requestReply = startCancellation(t, cancel, c)
				waitHeartbeatBlockedBy(t, s, cancelPID, regPID)
				if gate.Commit() != nil {
					t.Fatal("registration gate release failed")
				}
			} else {
				gate, pid := holdHeartbeatRow(t, s, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, run.TaskID)
				requestReply = startCancellation(t, cancel, c)
				waitHeartbeatBlockedBy(t, s, cancelPID, pid)
				go func() { regReply <- reg.registerExecution(regCtx, scope.registration) }()
				waitHeartbeatBlockedBy(t, s, regPID, cancelPID)
				if gate.Commit() != nil {
					t.Fatal("cancellation gate release failed")
				}
			}
			var regErr error
			select {
			case regErr = <-regReply:
			case <-testContext(t).Done():
				t.Fatal("registration deadline")
			}
			request := awaitCancellation(t, requestReply)
			if request.err != nil {
				t.Fatal("startup cancellation failed")
			}
			if k.spawn(scope, false) == nil {
				t.Fatal("cancellation permitted later spawn")
			}
			proofs := []cleanupObservation{}
			if winner == "registration" {
				if regErr != nil {
					t.Fatal("serialized registration disappeared")
				}
				p := keeperProof(t, k, scope, request.receipt)
				if p.Outcome != "NOT_STARTED" {
					t.Fatal("reserved scope had untracked execution")
				}
				persistProof(t, s, p)
				proofs = append(proofs, p)
			} else if !errors.Is(regErr, errCancellationRejected) {
				t.Fatal("post-cancel registration not rejected")
			}
			f := finalizationForTest(t, s, c, request.receipt, proofs)
			before := snapshotFor(t, s, run)
			r, err := s.finalizeCancellation(testContext(t), f)
			if err != nil {
				t.Fatal("complete startup inventory did not finalize")
			}
			checkFinalized(t, s, run, before, f, r)
		})
	}
}

func TestExecutionUnboundAndOlderEpochs(t *testing.T) {
	s, a, run, k := keeperRun(t)
	old := k.register()
	if k.spawn(old, true) != nil {
		t.Fatal("old scope unavailable")
	}
	first := keeperLease(t, s, k, old, 1)
	waitForLeaseExpiry(t, s, run.TaskID)
	current := k.register()
	if k.spawn(current, false) != nil {
		t.Fatal("current scope unavailable")
	}
	second := keeperLease(t, s, k, current, 60)
	if second.LeaseEpoch != first.LeaseEpoch+1 {
		t.Fatal("fixture did not reclaim")
	}
	unbound := k.register()
	if k.spawn(unbound, false) != nil {
		t.Fatal("unbound scope unavailable")
	}
	c, request := keeperCancellation(t, s, a, second, 1)
	p := keeperProof(t, k, current, request)
	persistProof(t, s, p)
	f := finalizationForTest(t, s, c, request, []cleanupObservation{p})
	before := snapshotFor(t, s, run)
	if _, err := s.finalizeCancellation(testContext(t), f); !errors.Is(err, errCancellationRejected) || snapshotFor(t, s, run) != before {
		t.Fatal("current epoch alone proved incomplete inventory")
	}
	oldProof, unboundProof := keeperProof(t, k, old, request), keeperProof(t, k, unbound, request)
	persistProof(t, s, oldProof)
	persistProof(t, s, unboundProof)
	oldBinding, err := readExecutionBinding(testContext(t), s.db, old.registration.ExecutionID)
	if err != nil || oldBinding.Task.LeaseEpoch != first.LeaseEpoch {
		t.Fatal("older ownership evidence was overwritten")
	}
	f = finalizationForTest(t, s, c, request, []cleanupObservation{p, oldProof, unboundProof})
	r, err := s.finalizeCancellation(testContext(t), f)
	if err != nil {
		t.Fatal("full old/current/unbound inventory did not finalize")
	}
	checkFinalized(t, s, run, before, f, r)
	t.Log("RF-07B complete inventory: old epoch, current owner and unbound gated child all reaped; no budget/attempt/delivery reset")
}

func TestExecutionStopRejectsInvalidWaitEvidence(t *testing.T) {
	for _, scenario := range []string{"unexpected-wait-error", "inconsistent-TERM", "inconsistent-KILL"} {
		t.Run(scenario, func(t *testing.T) {
			s, a, run, k := keeperRun(t)
			// Obtain a real non-signal exit status from a registered gated leaf.
			// Closing its private startup pipe makes the fixed fixture exit 2.
			reference := k.register()
			if k.spawn(reference, false) != nil || reference.input.Close() != nil {
				t.Fatal("reference wait evidence unavailable")
			}
			select {
			case <-reference.done:
			case <-testContext(t).Done():
				t.Fatal("reference reap deadline")
			}
			var exit *exec.ExitError
			if !errors.As(reference.waitErr, &exit) || exit.ExitCode() != 2 {
				t.Fatal("reference did not produce a real non-signal exit")
			}
			scope := k.register()
			scope.waitFault = reference.waitErr
			want := "SIGTERM was not observed"
			if scenario == "unexpected-wait-error" {
				scope.waitFault = errors.New("controlled invalid Wait result")
				want = "exit observation unavailable"
			} else if scenario == "inconsistent-KILL" {
				want = "SIGKILL was not observed"
			}
			// The target still undergoes actual TERM/KILL and cmd.Wait(). Only
			// the reported wait evidence is fault-injected before publication.
			if k.spawn(scope, scenario == "inconsistent-KILL") != nil {
				t.Fatal("wait-evidence leaf unavailable")
			}
			lease := keeperLease(t, s, k, scope, 60)
			c, request := keeperCancellation(t, s, a, lease, 1)
			before := snapshotFor(t, s, run)
			for attempt := 1; attempt <= 3; attempt++ {
				proof, err := k.stop(scope.registration, request, 100*time.Millisecond)
				if err == nil || err.Error() != want || proof != (cleanupObservation{}) {
					t.Errorf("stop attempt %d accepted invalid wait evidence or changed rejection: %v", attempt, err)
				}
				if scope.observation != nil {
					t.Errorf("stop attempt %d cached unverified cleanup evidence", attempt)
				}
			}
			status, ok := scope.command.ProcessState.Sys().(syscall.WaitStatus)
			wantSignal := syscall.SIGTERM
			if scenario == "inconsistent-KILL" {
				wantSignal = syscall.SIGKILL
			}
			if !ok || !status.Signaled() || status.Signal() != wantSignal {
				t.Fatal("target did not actually terminate and reap with the expected signal")
			}
			if scope.exitVerified || scope.outcome != "" || scope.status != 0 {
				t.Error("invalid wait evidence became a validated exit outcome")
			}
			if _, err := os.Lstat(scope.workspace); err != nil {
				t.Error("invalid wait evidence authorized workspace cleanup")
			}
			var count int
			if err := s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.workflow_execution_cleanups WHERE run_id=$1::uuid`, run.RunID).Scan(&count); err != nil || count != 0 || snapshotFor(t, s, run) != before {
				t.Fatal("invalid wait evidence changed durable cleanup or workflow state")
			}
			f := finalizationForTest(t, s, c, request, nil)
			if _, err := s.finalizeCancellation(testContext(t), f); !errors.Is(err, errCancellationRejected) {
				t.Fatal("unverified process exit permitted finalization")
			}
		})
	}
}

func TestExecutionCleanupEvidence(t *testing.T) {
	s, a, run, k := keeperRun(t)
	scope := k.register()
	if k.spawn(scope, false) != nil {
		t.Fatal("evidence leaf unavailable")
	}
	lease := keeperLease(t, s, k, scope, 60)
	c, request := keeperCancellation(t, s, a, lease, 1)
	before := snapshotFor(t, s, run)
	for _, scenario := range []string{"missing-operation", "conflicting-payload", "wrong-transition", "stale-version", "conflicting-inventory", "unavailable-evidence"} {
		if !t.Run("stop-request/"+scenario, func(t *testing.T) {
			invalid := request
			switch scenario {
			case "missing-operation":
				invalid.OperationID, _ = freshUUID()
			case "conflicting-payload":
				invalid.PayloadSHA256 = strings.Repeat("f", 64)
			case "wrong-transition":
				invalid.TransitionID, _ = freshUUID()
			case "stale-version":
				invalid.RunVersion++
			case "conflicting-inventory":
				original := k.inventory
				k.inventory.SyntheticPrefix = "synthetic:vn-t10-it-" + strings.Repeat("f", 32)
				defer func() { k.inventory = original }()
			case "unavailable-evidence":
				closed := storeForTest(t)
				if closed.Close() != nil {
					t.Fatal("unavailable evidence fixture did not close")
				}
				k.store = closed
				defer func() { k.store = s }()
			}
			if _, err := k.stop(scope.registration, invalid, time.Millisecond); err == nil {
				t.Fatal("invalid same-run cancellation receipt authorized termination")
			}
			select {
			case <-scope.done:
				t.Fatal("invalid cancellation terminated the owned leaf")
			default:
			}
			if scope.command.Process.Signal(syscall.Signal(0)) != nil || scope.closed || scope.observation != nil {
				t.Fatal("rejected request changed live process/gate/evidence")
			}
			var count int
			if err := s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.workflow_execution_cleanups WHERE run_id=$1::uuid`, run.RunID).Scan(&count); err != nil || count != 0 || snapshotFor(t, s, run) != before {
				t.Fatal("rejected stop request persisted cleanup or progress")
			}
		}) {
			return
		}
	}
	wrong := scope.registration
	wrong.Keeper.Start++
	if _, err := k.stop(wrong, request, time.Millisecond); !errors.Is(err, errCancellationRejected) {
		t.Fatal("keeper accepted supplied identity instead of owned handle")
	}
	missingKeeper := &cancellationKeeper{t: t, store: s, inventory: k.inventory, scopes: map[string]*ownedCancellationScope{}}
	if _, err := missingKeeper.stop(scope.registration, request, time.Millisecond); !errors.Is(err, errCancellationRejected) {
		t.Fatal("persisted identity was used to adopt a process without original handles")
	}
	k.inventory.Keeper.Start++
	if _, err := k.stop(scope.registration, request, time.Millisecond); !errors.Is(err, errCancellationRejected) {
		t.Fatal("changed keeper incarnation retained authority")
	}
	k.inventory.Keeper.Start--
	select {
	case <-scope.done:
		t.Fatal("rejected ownership check terminated the live child")
	default:
	}
	if scope.closed {
		t.Fatal("rejected ownership check closed the real scope")
	}
	// Actual stop/reap happens, but controlled workspace cleanup is withheld.
	scope.cleanupBlocked = true
	if _, err := k.stop(scope.registration, request, 100*time.Millisecond); err == nil {
		t.Fatal("missing workspace cleanup produced evidence")
	}
	if !scope.exitVerified || scope.outcome != "TERM" || scope.status != int(syscall.SIGTERM) || scope.observation != nil {
		t.Fatal("cleanup failure lost verified TERM/reap evidence or created premature proof")
	}
	f := finalizationForTest(t, s, c, request, nil)
	if _, err := s.finalizeCancellation(testContext(t), f); !errors.Is(err, errCancellationRejected) {
		t.Fatal("incomplete cleanup finalized")
	}
	scope.cleanupBlocked = false
	proof := keeperProof(t, k, scope, request)
	if proof.Outcome != "TERM" || proof.WaitStatus != int(syscall.SIGTERM) || !proof.Reaped {
		t.Fatal("cleanup retry did not preserve the validated exit evidence")
	}
	for name, change := range map[string]func(*cleanupObservation){
		"open":               func(p *cleanupObservation) { p.ScopeClosed = false },
		"not-reaped":         func(p *cleanupObservation) { p.Reaped = false },
		"unclean":            func(p *cleanupObservation) { p.WorkspaceClean = false },
		"keeper-incarnation": func(p *cleanupObservation) { p.Scope.Keeper.Start++ },
		"unknown-scope":      func(p *cleanupObservation) { p.Scope.ExecutionID = "11111111-1111-4111-8111-111111111111" },
		"wrong-request":      func(p *cleanupObservation) { p.RequestTransitionID = "11111111-1111-4111-8111-111111111111" },
	} {
		t.Run(name, func(t *testing.T) {
			p := proof
			change(&p)
			if err := s.recordExecutionCleanup(testContext(t), p); !errors.Is(err, errCancellationRejected) {
				t.Fatal("untrusted/stale/incomplete evidence accepted")
			}
		})
	}
	persistProof(t, s, proof)
	conflict := proof
	conflict.WaitStatus++
	if err := s.recordExecutionCleanup(testContext(t), conflict); !errors.Is(err, errCancellationConflict) {
		t.Fatal("changed cleanup replay was not conflict")
	}
	conflict = proof
	conflict.ObservationID, _ = freshUUID()
	if err := s.recordExecutionCleanup(testContext(t), conflict); !errors.Is(err, errCancellationConflict) {
		t.Fatal("second observation silently replaced evidence")
	}
	f = finalizationForTest(t, s, c, request, []cleanupObservation{proof})
	stale := f
	stale.ExpectedRunVersion++
	if _, err := s.finalizeCancellation(testContext(t), stale); !errors.Is(err, errCancellationRejected) {
		t.Fatal("stale finalization accepted")
	}
	stale = f
	stale.CleanupSHA256 = strings.Repeat("f", 64)
	if _, err := s.finalizeCancellation(testContext(t), stale); !errors.Is(err, errCancellationRejected) {
		t.Fatal("caller hash was treated as evidence authority")
	}
	if snapshotFor(t, s, run) != before {
		t.Fatal("rejected cleanup/finalization changed durable progress")
	}
}

func TestCancellationFinalizationLegacy(t *testing.T) {
	t.Run("legacy-queued-cannot-enroll", func(t *testing.T) {
		s := storeForTest(t)
		a, run := admitForTest(t, s, "legacy-queued")
		before := snapshotFor(t, s, run)
		id, _ := freshUUID()
		if _, err := s.admitExecution(testContext(t), a, currentKeeperIdentity(t, id)); !errors.Is(err, errCancellationRejected) || snapshotFor(t, s, run) != before {
			t.Fatal("queued legacy row was treated as proof of complete ownership")
		}
	})
	t.Run("legacy-pending", func(t *testing.T) {
		s := storeForTest(t)
		a, run := admitForTest(t, s, "legacy-pending")
		lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-legacy", 60)
		if err != nil {
			t.Fatal("legacy claim failed")
		}
		c := cancellationForTest(t, a, lease, 1)
		r, err := s.requestCancellation(testContext(t), c)
		if err != nil {
			t.Fatal("legacy request failed")
		}
		before := snapshotFor(t, s, run)
		id, _ := freshUUID()
		f := cancellationFinalization{OperationID: id, Request: c, ExpectedRunVersion: r.RunVersion, InventorySHA256: strings.Repeat("a", 64), CleanupSHA256: strings.Repeat("b", 64)}
		if _, err := s.finalizeCancellation(testContext(t), f); !errors.Is(err, errCancellationRejected) || snapshotFor(t, s, run) != before {
			t.Fatal("empty legacy ledger became complete inventory")
		}
		keeperID, _ := freshUUID()
		if _, err := s.admitExecution(testContext(t), a, currentKeeperIdentity(t, keeperID)); !errors.Is(err, errCancellationRejected) {
			t.Fatal("legacy cancellation was adopted without pre-execution proof")
		}
	})
	t.Run("trusted-empty", func(t *testing.T) {
		s, a, run, _ := keeperRun(t)
		c, r := keeperCancellation(t, s, a, TaskLease{RunID: run.RunID, TaskID: run.TaskID, AttemptID: run.AttemptID, Stage: "INVESTIGATING"}, 1)
		before := snapshotFor(t, s, run)
		f := finalizationForTest(t, s, c, r, nil)
		got, err := s.finalizeCancellation(testContext(t), f)
		if err != nil {
			t.Fatal("explicit pre-execution completeness attestation not recognized")
		}
		checkFinalized(t, s, run, before, f, got)
	})
}

func readyFinalization(t *testing.T) (*Store, AdmissionReceipt, cancellationRequest, cancellationReceipt, cancellationFinalization) {
	t.Helper()
	s, a, run, k := keeperRun(t)
	scope := k.register()
	if k.spawn(scope, false) != nil {
		t.Fatal("ready leaf unavailable")
	}
	lease := keeperLease(t, s, k, scope, 60)
	c, r := keeperCancellation(t, s, a, lease, 1)
	p := keeperProof(t, k, scope, r)
	persistProof(t, s, p)
	return s, run, c, r, finalizationForTest(t, s, c, r, []cleanupObservation{p})
}

type finalizationReply struct {
	receipt finalizationReceipt
	err     error
}

func startFinalization(t *testing.T, s *Store, c cancellationFinalization) <-chan finalizationReply {
	t.Helper()
	ctx := testContext(t)
	reply := make(chan finalizationReply, 1)
	go func() { r, err := s.finalizeCancellation(ctx, c); reply <- finalizationReply{r, err} }()
	return reply
}
func awaitFinalization(t *testing.T, reply <-chan finalizationReply) finalizationReply {
	t.Helper()
	select {
	case r := <-reply:
		return r
	case <-testContext(t).Done():
		t.Fatal("finalization deadline")
		return finalizationReply{}
	}
}

func TestCancellationFinalizationConcurrency(t *testing.T) {
	for _, same := range []bool{true, false} {
		name := "different-operations"
		if same {
			name = "exact-replay"
		}
		t.Run(name, func(t *testing.T) {
			s, run, _, _, f := readyFinalization(t)
			before := snapshotFor(t, s, run)
			one, pid1 := heartbeatProbe(t)
			two, pid2 := heartbeatProbe(t)
			one.databaseURL, two.databaseURL = s.databaseURL, s.databaseURL
			gate, gatePID := holdHeartbeatRow(t, s, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, run.TaskID)
			a := startFinalization(t, one, f)
			waitHeartbeatBlockedBy(t, s, pid1, gatePID)
			other := f
			if !same {
				other.OperationID, _ = freshUUID()
			}
			b := startFinalization(t, two, other)
			waitHeartbeatBlockedBy(t, s, pid2, pid1)
			if gate.Commit() != nil {
				t.Fatal("finalization gate release failed")
			}
			x, y := awaitFinalization(t, a), awaitFinalization(t, b)
			if x.err != nil {
				t.Fatal("first finalization failed")
			}
			if same {
				if y.err != nil || y.receipt != x.receipt {
					t.Fatal("concurrent exact replay changed receipt")
				}
			} else if !errors.Is(y.err, errCancellationRejected) {
				t.Fatal("second finalization advanced terminal state")
			}
			checkFinalized(t, s, run, before, f, x.receipt)
			conflict := f
			conflict.InventorySHA256 = strings.Repeat("f", 64)
			if _, err := s.finalizeCancellation(testContext(t), conflict); !errors.Is(err, errCancellationConflict) {
				t.Fatal("changed finalization payload was not conflict")
			}
		})
	}
}

func TestCancellationFinalizationControllerCrashes(t *testing.T) {
	s, a, run, k := keeperRun(t)
	scope := k.register()
	if k.spawn(scope, false) != nil {
		t.Fatal("crash leaf unavailable")
	}
	lease := keeperLease(t, s, k, scope, 60)
	c, request := keeperCancellation(t, s, a, lease, 1)
	proof := keeperProof(t, k, scope, request) // Actual keeper retains reaped handle/observation.
	f := finalizationForTest(t, s, c, request, []cleanupObservation{proof})
	config := finalizationControllerConfig{DatabaseURL: s.databaseURL, Nonce: os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"),
		Prefix: c.SyntheticPrefix, Mode: "before-evidence", Observations: []cleanupObservation{proof}, Finalization: f}
	before := snapshotFor(t, s, run)
	unsafe := config
	unsafe.Mode = "after-finalize"
	if unsafe.Nonce[0] == '0' {
		unsafe.Nonce = "1" + unsafe.Nonce[1:]
	} else {
		unsafe.Nonce = "0" + unsafe.Nonce[1:]
	}
	denied := startFinalizationController(t, unsafe)
	awaitResultControllerExit(t, denied)
	var deniedExit *exec.ExitError
	if !errors.As(denied.waitErr, &deniedExit) || deniedExit.ExitCode() != 3 || len(awaitResultACK(t, denied)) != 0 || snapshotFor(t, s, run) != before {
		t.Fatal("finalization child bypassed B0 proof")
	}
	first := startFinalizationController(t, config)
	awaitFinalizationCheckpoint(t, first)
	var count int
	if err := s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.workflow_execution_cleanups WHERE run_id=$1::uuid`, run.RunID).Scan(&count); err != nil || count != 0 {
		t.Fatal("before-proof controller persisted evidence early")
	}
	killFinalizationController(t, first)
	if snapshotFor(t, s, run) != before {
		t.Fatal("controller crash falsely finalized cleanup")
	}
	recoveredProof := keeperProof(t, k, scope, request)
	if recoveredProof != proof {
		t.Fatal("surviving keeper did not recover original ownership/observation")
	}
	config.Mode = "after-finalize"
	second := startFinalizationController(t, config)
	awaitFinalizationCheckpoint(t, second)
	observer := storeForTest(t)
	r, err := observer.reconcileFinalization(testContext(t), f)
	if err != nil {
		t.Fatal("independent observer could not prove finalization COMMIT")
	}
	checkFinalized(t, observer, run, before, f, r)
	accepted := snapshotFor(t, s, run)
	killFinalizationController(t, second)
	reconnect := storeForTest(t)
	replay, err := reconnect.finalizeCancellation(testContext(t), f)
	if err != nil || replay != r || snapshotFor(t, s, run) != accepted {
		t.Fatal("post-COMMIT pre-ACK crash duplicated finalization")
	}
	// Positive control: same original proof/operation returns its ACK after release.
	third := startFinalizationController(t, config)
	awaitFinalizationCheckpoint(t, third)
	if json.NewEncoder(third.input).Encode("ack") != nil {
		t.Fatal("finalization ACK gate release failed")
	}
	var ack finalizationReceipt
	if json.Unmarshal(awaitResultACK(t, third), &ack) != nil || !ack.Control.RequestedAt.Equal(r.Control.RequestedAt) {
		t.Fatal("finalization ACK control receipt mismatch")
	}
	comparison := ack
	comparison.Control.RequestedAt = r.Control.RequestedAt
	if comparison != r {
		t.Fatal("finalization ACK control identity mismatch")
	}
	awaitResultControllerExit(t, third)
	if third.waitErr != nil {
		t.Fatal("positive finalization controller failed")
	}
	t.Log("RF-07B actual controller SIGKILL/reap: before evidence remains pending; surviving keeper reuses original observation; final COMMIT independently observed before zero-byte ACK crash; replay unchanged")
}

func TestCancellationFinalizationRollback(t *testing.T) {
	s, run, _, request, f := readyFinalization(t)
	id, _ := freshUUID()
	// Fault fixture only: reserve the audit version, forcing a late INSERT error.
	_, err := s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_transitions
        (transition_id,run_id,attempt_id,from_state,to_state,prior_run_version)
        VALUES($1::uuid,$2::uuid,$3::uuid,'CANCEL_REQUESTED','BLOCKED',$4)`, id, run.RunID, run.AttemptID, request.RunVersion)
	if err != nil {
		t.Fatal("late finalization fault fixture failed")
	}
	before := snapshotFor(t, s, run)
	_, err = s.finalizeCancellation(testContext(t), f)
	var pgerr *pgconn.PgError
	if !errors.As(err, &pgerr) || pgerr.Code != "23505" || errors.Is(err, errCancellationConflict) || errors.Is(err, errCancellationUnknown) || snapshotFor(t, s, run) != before {
		t.Fatal("late finalization failure lost rollback/database-error distinction")
	}
	if _, err := s.reconcileFinalization(testContext(t), f); !errors.Is(err, errCancellationUnknown) {
		t.Fatal("failed finalization fabricated receipt")
	}
}

func TestCancellationFinalizationCommitResponseLoss(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s, run, _, _, f := readyFinalization(t)
			before := snapshotFor(t, s, run)
			url, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), url)
			if err != nil {
				t.Fatal("finalization proxy unavailable")
			}
			t.Cleanup(func() { _ = faulty.Close() })
			start := time.Now()
			r, err := faulty.finalizeCancellation(testContext(t), f)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("finalization COMMIT fault not observed")
			}
			if time.Since(start) > 4*time.Second {
				t.Fatal("finalization reconciliation exceeded bound")
			}
			if mode == "after-confirm" {
				if err != nil {
					t.Fatal("confirmed finalization not recovered")
				}
				checkFinalized(t, s, run, before, f, r)
			} else {
				if !errors.Is(err, errCancellationUnknown) || r != (finalizationReceipt{}) {
					t.Fatal("uncertain finalization falsely acknowledged")
				}
				if mode == "before-forward" {
					if snapshotFor(t, s, run) != before {
						t.Fatal("unforwarded COMMIT changed state")
					}
					if _, err := s.reconcileFinalization(testContext(t), f); !errors.Is(err, errCancellationUnknown) {
						t.Fatal("absence resolved UNKNOWN")
					}
				} else {
					recovered, err := s.reconcileFinalization(testContext(t), f)
					if err != nil {
						t.Fatal("healthy finalization recovery failed")
					}
					checkFinalized(t, s, run, before, f, recovered)
				}
			}
		})
	}
}

func TestExecutionAppendOnlyAndFinalizationConstraints(t *testing.T) {
	s, _, _, _, f := readyFinalization(t)
	for _, table := range []string{"workflow_execution_inventories", "workflow_execution_scopes", "workflow_execution_bindings", "workflow_cancellation_inventories", "workflow_execution_cleanups"} {
		for _, statement := range []string{"UPDATE orchestration." + table + " SET run_id=run_id WHERE false", "DELETE FROM orchestration." + table + " WHERE false", "TRUNCATE orchestration." + table} {
			_, err := s.db.ExecContext(testContext(t), statement)
			var pgerr *pgconn.PgError
			if !errors.As(err, &pgerr) || pgerr.Code != "42501" {
				t.Fatal("runtime could rewrite ownership/cleanup evidence")
			}
		}
	}
	r, err := s.finalizeCancellation(testContext(t), f)
	if err != nil {
		t.Fatal("finalization constraint fixture failed")
	}
	for _, scenario := range []string{"missing-request", "wrong-inventory", "null-control"} {
		t.Run(scenario, func(t *testing.T) {
			id, _ := freshUUID()
			op, _ := freshUUID()
			var request any = r.RequestTransitionID
			inventory := r.InventorySHA256
			if scenario == "missing-request" {
				request = nil
			}
			if scenario == "wrong-inventory" {
				inventory = strings.Repeat("f", 64)
			}
			var err error
			if scenario == "null-control" {
				_, err = s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_transitions
                (transition_id,run_id,attempt_id,from_state,to_state,prior_run_version)
                SELECT $1::uuid,run_id,attempt_id,'CANCEL_REQUESTED','CANCELLED',prior_run_version+1
                FROM orchestration.workflow_transitions WHERE transition_id=$2::uuid`, id, r.Control.TransitionID)
			} else {
				_, err = s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_transitions
                (transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,control_operation_id,
                control_kind,control_digest_version,control_payload_sha256,control_task_id,control_claimant_id,
                control_lease_epoch,control_policy_epoch,control_source_fingerprint_sha256,
                control_request_transition_id,control_inventory_sha256,control_cleanup_sha256)
                SELECT $1::uuid,run_id,attempt_id,from_state,to_state,prior_run_version+1,$2::uuid,
                control_kind,control_digest_version,control_payload_sha256,control_task_id,control_claimant_id,
                control_lease_epoch,control_policy_epoch,control_source_fingerprint_sha256,$3::uuid,$4,control_cleanup_sha256
                FROM orchestration.workflow_transitions WHERE transition_id=$5::uuid`, id, op, request, inventory, r.Control.TransitionID)
			}
			var pgerr *pgconn.PgError
			if !errors.As(err, &pgerr) {
				t.Fatal("malformed finalization audit accepted")
			}
			if scenario == "wrong-inventory" {
				if pgerr.Code != "23503" || pgerr.ConstraintName != "fk_cancel_finalize_request" {
					t.Fatal("finalization not bound to actual frozen inventory")
				}
			} else if pgerr.Code != "23514" || pgerr.ConstraintName != "ck_cancel_request_metadata" {
				t.Fatal("finalization metadata CHECK missing")
			}
		})
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := s.confirmExecution(ctx, func(ctx context.Context, db executionReader) error {
		var n int
		return db.QueryRowContext(ctx, "SELECT 1").Scan(&n)
	}); !errors.Is(err, errCancellationUnknown) || !errors.Is(err, context.Canceled) {
		t.Fatal("execution lookup lost parent bound/UNKNOWN")
	}
}

func TestCancellationFinalizationPreservesAcceptedResult(t *testing.T) {
	s, a, run, k := keeperRun(t)
	old := k.register()
	if k.spawn(old, false) != nil {
		t.Fatal("investigation leaf unavailable")
	}
	lease := keeperLease(t, s, k, old, 60)
	advance := validAdvance(t, a, lease)
	result, err := s.advanceInvestigation(testContext(t), advance)
	if err != nil {
		t.Fatal("accepted result fixture failed")
	}
	current := k.register()
	if k.spawn(current, false) != nil {
		t.Fatal("planning leaf unavailable")
	}
	planning, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-planning", 60)
	if err != nil {
		t.Fatal("planning claim failed")
	}
	if k.release(current, executionBinding{Scope: current.registration, Task: planning, RunVersion: 2}) != nil {
		t.Fatal("planning binding failed")
	}
	c, request := keeperCancellation(t, s, a, planning, 2)
	p, q := keeperProof(t, k, old, request), keeperProof(t, k, current, request)
	persistProof(t, s, p)
	persistProof(t, s, q)
	f := finalizationForTest(t, s, c, request, []cleanupObservation{p, q})
	target := AdmissionReceipt{RunID: run.RunID, TaskID: result.NextTaskID, AttemptID: result.NextAttemptID}
	before, oldBefore := snapshotFor(t, s, target), snapshotFor(t, s, run)
	r, err := s.finalizeCancellation(testContext(t), f)
	if err != nil {
		t.Fatal("planning finalization failed")
	}
	checkFinalized(t, s, target, before, f, r)
	want := oldBefore
	want.state = "CANCELLED"
	want.version++
	want.transitions++
	want.outstanding--
	if snapshotFor(t, s, run) != want {
		t.Fatal("finalization overwrote prior accepted completion/history")
	}
	replay, err := s.advanceInvestigation(testContext(t), advance)
	if err != nil || replay != result || snapshotFor(t, s, run) != want {
		t.Fatal("terminal cancellation erased or duplicated accepted V5 receipt")
	}
	requireCancellationRaceBeforeExpiry(t, s, planning)
}

func TestExecutionCommitResponseLoss(t *testing.T) {
	for _, operation := range []string{"enroll", "register", "bind", "cleanup"} {
		for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
			t.Run(operation+"/"+mode, func(t *testing.T) {
				s := storeForTest(t)
				a := admissionForTest(t, "execution-commit")
				var apply func(*Store) error
				var confirm func(context.Context, executionReader) error
				if operation == "enroll" {
					id, _ := freshUUID()
					inv := executionInventory{SyntheticPrefix: os.Getenv("ASEP_VN_T10_TEST_PREFIX"), Keeper: currentKeeperIdentity(t, id)}
					apply = func(s *Store) error { _, err := s.admitExecution(testContext(t), a, inv.Keeper); return err }
					confirm = func(ctx context.Context, db executionReader) error {
						_, err := lookupExecutionAdmission(ctx, db, a, inv)
						return err
					}
				} else {
					id, _ := freshUUID()
					run, err := s.admitExecution(testContext(t), a, currentKeeperIdentity(t, id))
					if err != nil {
						t.Fatal("bookkeeping execution admission failed")
					}
					k := newCancellationKeeper(t, s, run)
					if operation == "register" {
						id, _ := freshUUID()
						scope := executionScope{ExecutionID: id, RunID: run.RunID, Keeper: k.inventory.Keeper}
						apply = func(s *Store) error { return s.registerExecution(testContext(t), scope) }
						confirm = func(ctx context.Context, db executionReader) error {
							got, err := readExecutionScope(ctx, db, id)
							if err != nil {
								return err
							}
							if got != scope {
								return errCancellationConflict
							}
							return nil
						}
					} else {
						scope := k.register()
						if k.spawn(scope, false) != nil {
							t.Fatal("commit scope unavailable")
						}
						lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-commit", 60)
						if err != nil {
							t.Fatal("commit binding claim failed")
						}
						binding := executionBinding{Scope: scope.registration, Task: lease, RunVersion: 1}
						if operation == "bind" {
							apply = func(s *Store) error { return s.bindExecution(testContext(t), binding) }
							want := binding
							want.Task.ExpiresAt = time.Time{}
							want.Task.DeliveryCount = 0
							confirm = func(ctx context.Context, db executionReader) error {
								got, err := readExecutionBinding(ctx, db, scope.registration.ExecutionID)
								if err != nil {
									return err
								}
								if got != want {
									return errCancellationConflict
								}
								return nil
							}
						} else {
							if k.release(scope, binding) != nil {
								t.Fatal("cleanup commit binding failed")
							}
							a := Admission{PolicyEpoch: 1, SourceFingerprintSHA256: strings.Repeat("b", 64)}
							_, r := keeperCancellation(t, s, a, lease, 1)
							p := keeperProof(t, k, scope, r)
							apply = func(s *Store) error { return s.recordExecutionCleanup(testContext(t), p) }
							confirm = func(ctx context.Context, db executionReader) error {
								got, err := readCleanup(ctx, db, p.ObservationID)
								if err != nil {
									return err
								}
								if got != p {
									return errCancellationConflict
								}
								return nil
							}
						}
					}
				}
				url, injected := commitFaultProxy(t, mode)
				faulty, err := Open(testContext(t), url)
				if err != nil {
					t.Fatal("execution COMMIT proxy unavailable")
				}
				t.Cleanup(func() { _ = faulty.Close() })
				start := time.Now()
				err = apply(faulty)
				select {
				case <-injected:
				case <-testContext(t).Done():
					t.Fatal("execution COMMIT fault not reached")
				}
				if time.Since(start) > 4*time.Second {
					t.Fatal("execution reconciliation exceeded bound")
				}
				if mode == "after-confirm" {
					if err != nil {
						t.Fatal("confirmed immutable execution identity not recovered")
					}
				} else if !errors.Is(err, errCancellationUnknown) {
					t.Fatal("uncertain execution bookkeeping falsely acknowledged")
				}
				healthy := s.confirmExecution(testContext(t), confirm)
				if mode == "before-forward" {
					if !errors.Is(healthy, errCancellationUnknown) {
						t.Fatal("absent bookkeeping record resolved UNKNOWN")
					}
				} else if healthy != nil {
					t.Fatal("original execution operation did not reconcile independently")
				}
			})
		}
	}
}
