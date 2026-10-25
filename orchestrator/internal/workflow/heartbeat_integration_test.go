//go:build integration

package workflow

import (
	"errors"
	"testing"
)

func TestHeartbeatBindsAttemptAndStage(t *testing.T) {
	store := storeForTest(t)
	_, run := admitForTest(t, store, "heartbeat-full-binding")
	lease, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-heartbeat-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	before := snapshotFor(t, store, run)
	changed := lease
	changed.Stage = "PLANNING"
	if _, err = store.Heartbeat(testContext(t), changed, 30); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("tampered stage accepted: %v", err)
	}
	if after := snapshotFor(t, store, run); after != before {
		t.Fatal("tampered stage changed persisted lease authority")
	}
	changed = lease
	changed.AttemptID, _ = freshUUID()
	if _, err = store.Heartbeat(testContext(t), changed, 30); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("tampered attempt accepted: %v", err)
	}
	if after := snapshotFor(t, store, run); after != before {
		t.Fatal("tampered attempt changed persisted lease authority")
	}
	expires, err := store.Heartbeat(testContext(t), lease, 60)
	if err != nil {
		t.Fatalf("valid lease rejected after tamper tests: %v", err)
	}
	expected := before
	expected.firstTaskExpiry.Time = expires
	if after := snapshotFor(t, store, run); after != expected || !expires.After(before.firstTaskExpiry.Time) {
		t.Fatalf("valid heartbeat changed authority or failed to extend expiry: %+v", after)
	}
	t.Log("valid heartbeat extended persisted expiry; owner/epoch/delivery/attempt/budget/version unchanged")
}
