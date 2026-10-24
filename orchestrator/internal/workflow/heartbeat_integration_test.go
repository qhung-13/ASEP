//go:build integration

package workflow

import (
	"context"
	"errors"
	"testing"
)

func TestHeartbeatBindsAttemptAndStage(t *testing.T) {
	store := storeForTest(t)
	_, run := admitForTest(t, store, "heartbeat-full-binding")
	lease, err := store.ClaimForRun(context.Background(), run.RunID, "synthetic-heartbeat-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	changed := lease
	changed.Stage = "PLANNING"
	if _, err = store.Heartbeat(context.Background(), changed, 30); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("tampered stage accepted: %v", err)
	}
	changed = lease
	changed.AttemptID, _ = freshUUID()
	if _, err = store.Heartbeat(context.Background(), changed, 30); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("tampered attempt accepted: %v", err)
	}
	if _, err = store.Heartbeat(context.Background(), lease, 30); err != nil {
		t.Fatalf("valid lease rejected after tamper tests: %v", err)
	}
}
