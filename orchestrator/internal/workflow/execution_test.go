package workflow

import (
	"strings"
	"testing"
)

func canonicalCleanupFixture() cleanupObservation {
	r := canonicalResultFixture()
	return cleanupObservation{ObservationID: r.OperationID, RequestTransitionID: r.Lease.RunID,
		Scope: executionScope{ExecutionID: r.Lease.TaskID, RunID: r.Lease.RunID,
			Keeper: keeperIdentity{ID: r.Lease.AttemptID, BootID: r.Lease.RunID, PID: 42, Start: 123}},
		Outcome: "TERM", WaitStatus: 15, ScopeClosed: true, Reaped: true, WorkspaceClean: true}
}

func TestExecutionCanonicalEvidence(t *testing.T) {
	p := canonicalCleanupFixture()
	other := p
	other.ObservationID = "55555555-5555-4555-8555-555555555555"
	other.Scope.ExecutionID = other.ObservationID
	a, err := cleanupSetDigest(p.RequestTransitionID, strings.Repeat("a", 64), []cleanupObservation{p, other})
	if err != nil || !isLowerHex(a, 64) {
		t.Fatal("cleanup digest unavailable")
	}
	b, err := cleanupSetDigest(p.RequestTransitionID, strings.Repeat("a", 64), []cleanupObservation{other, p})
	if err != nil || b != a {
		t.Fatal("cleanup evidence depends on delivery order")
	}
	other.WaitStatus++
	if b, _ := cleanupSetDigest(p.RequestTransitionID, strings.Repeat("a", 64), []cleanupObservation{p, other}); b == a {
		t.Fatal("changed observation did not change digest")
	}
	for name, change := range map[string]func(*cleanupObservation){
		"request":   func(p *cleanupObservation) { p.RequestTransitionID = other.ObservationID },
		"scope":     func(p *cleanupObservation) { p.Scope.ExecutionID = other.ObservationID },
		"keeper":    func(p *cleanupObservation) { p.Scope.Keeper.Start++ },
		"outcome":   func(p *cleanupObservation) { p.Outcome = "KILL" },
		"closed":    func(p *cleanupObservation) { p.ScopeClosed = false },
		"reaped":    func(p *cleanupObservation) { p.Reaped = false },
		"workspace": func(p *cleanupObservation) { p.WorkspaceClean = false },
	} {
		t.Run(name, func(t *testing.T) {
			x := p
			change(&x)
			want, _ := p.digest()
			got, _ := x.digest()
			if got == want {
				t.Fatal("cleanup semantic identity omitted")
			}
		})
	}
	f := cancellationFinalization{OperationID: p.ObservationID, Request: canonicalCancellationFixture(), ExpectedRunVersion: 2,
		InventorySHA256: strings.Repeat("a", 64), CleanupSHA256: strings.Repeat("b", 64)}
	want, _ := f.digest()
	replay := f
	replay.OperationID = other.ObservationID
	if got, _ := replay.digest(); got != want {
		t.Fatal("finalization envelope key altered canonical body")
	}
	replay = f
	replay.Request.OperationID = other.ObservationID
	if got, _ := replay.digest(); got == want {
		t.Fatal("original request identity omitted from finalization")
	}
	replay = f
	replay.CleanupSHA256 = strings.Repeat("c", 64)
	if got, _ := replay.digest(); got == want {
		t.Fatal("cleanup evidence not bound to finalization")
	}
}

func TestExecutionRejectsIncompleteProof(t *testing.T) {
	p := canonicalCleanupFixture()
	for name, change := range map[string]func(*cleanupObservation){
		"observation": func(p *cleanupObservation) { p.ObservationID = "" },
		"scope":       func(p *cleanupObservation) { p.Scope.ExecutionID = "" },
		"keeper":      func(p *cleanupObservation) { p.Scope.Keeper.Start = 0 },
		"signal-only": func(p *cleanupObservation) { p.Reaped = false },
		"open-gate":   func(p *cleanupObservation) { p.ScopeClosed = false },
		"workspace":   func(p *cleanupObservation) { p.WorkspaceClean = false },
		"not-started": func(p *cleanupObservation) { p.Outcome = "NOT_STARTED" },
	} {
		t.Run(name, func(t *testing.T) {
			x := p
			change(&x)
			if err := (&Store{}).recordExecutionCleanup(t.Context(), x); err != errCancellationRejected {
				t.Fatal("incomplete proof reached database")
			}
		})
	}
}
