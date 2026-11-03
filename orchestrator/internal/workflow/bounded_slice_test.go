package workflow

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestSliceFiniteProfileValidation(t *testing.T) {
	good := SliceLimits{"synthetic:vn-t10-it-" + strings.Repeat("a", 32), 180, 512, 4}
	if !good.valid() {
		t.Fatal("finite synthetic profile rejected")
	}
	for _, bad := range []SliceLimits{{"live", 180, 512, 4}, {good.ScopePrefix, 0, 512, 4}, {good.ScopePrefix, 301, 512, 4}, {good.ScopePrefix, 180, 0, 4}, {good.ScopePrefix, 180, 512, 9}} {
		if bad.valid() {
			t.Fatal("unbounded or live profile accepted")
		}
	}
	s := &Store{}
	if _, err := s.AdmitSyntheticSlice(context.Background(), Admission{}, good); !errors.Is(err, ErrTransitionRejected) {
		t.Fatal("scope checked too late")
	}
	if _, err := s.ReserveSliceAgentTask(context.Background(), TaskLease{Stage: "DELIVERY_PENDING"}, "bad", "success"); !errors.Is(err, ErrTransitionRejected) {
		t.Fatal("delivery dispatched to model")
	}
}
func TestSliceLogicalResultIdentityExcludesMutableTransportMetadata(t *testing.T) {
	c := SliceAdvance{OperationID: "11111111-1111-4111-8111-111111111111", Lease: TaskLease{RunID: "22222222-2222-4222-8222-222222222222", TaskID: "33333333-3333-4333-8333-333333333333", Stage: "PLANNING", LeaseEpoch: 1, ExpiresAt: time.Now()}, ExpectedRunVersion: 2, PolicyEpoch: 1, SourceFingerprint: strings.Repeat("a", 64), EvidenceRef: "synthetic:plan"}
	first, err := sliceAdvanceDigest(c)
	if err != nil {
		t.Fatal(err)
	}
	c.Lease.ExpiresAt = c.Lease.ExpiresAt.Add(time.Minute)
	c.Lease.DeliveryCount++
	second, _ := sliceAdvanceDigest(c)
	if first != second {
		t.Fatal("transport renewal changed logical result")
	}
	c.Lease.LeaseEpoch++
	third, _ := sliceAdvanceDigest(c)
	if third == second {
		t.Fatal("fencing epoch lost from identity")
	}
}
