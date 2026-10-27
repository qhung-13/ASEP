package workflow

import (
	"strings"
	"testing"
	"time"
)

func TestRetryCanonicalIdentity(t *testing.T) {
	id := "11111111-1111-4111-8111-111111111111"
	c := retryDecision{OperationID: id, Kind: "REWORK", Lease: TaskLease{RunID: id, TaskID: id, AttemptID: id, Stage: "PLANNING", ClaimantID: "synthetic", LeaseEpoch: 1}, ExpectedRunVersion: 2, PolicyEpoch: 1, SourceSHA256: strings.Repeat("a", 64), EvidenceRef: "synthetic:vn-t10-it-" + strings.Repeat("a", 32) + ":gate"}
	if c.validate() != nil {
		t.Fatal("valid synthetic decision rejected")
	}
	hash, err := c.digest()
	if err != nil {
		t.Fatal(err)
	}
	c.Lease.ExpiresAt = time.Now()
	c.Lease.DeliveryCount = 999
	if got, _ := c.digest(); got != hash {
		t.Fatal("mutable transport facts changed logical identity")
	}
	c.Kind = "REPLAN"
	if got, _ := c.digest(); got == hash {
		t.Fatal("distinct semantic decisions shared a digest")
	}
	c.Kind = "ADVANCE"
	if c.validate() == nil {
		t.Fatal("unapproved synthetic decision admitted")
	}
}
