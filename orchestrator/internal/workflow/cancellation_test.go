package workflow

import (
	"strings"
	"testing"
	"time"
)

func canonicalCancellationFixture() cancellationRequest {
	r := canonicalResultFixture()
	return cancellationRequest{OperationID: r.OperationID, Task: r.Lease,
		ExpectedRunVersion: r.ExpectedRunVersion, ExpectedPolicyEpoch: r.ExpectedPolicyEpoch,
		ExpectedSourceFingerprintSHA256: r.ExpectedSourceFingerprintSHA256,
		SyntheticPrefix:                 "synthetic:vn-t10-it-" + strings.Repeat("a", 32)}
}

func TestCancellationCanonicalPayload(t *testing.T) {
	c := canonicalCancellationFixture()
	want, err := c.canonicalPayloadSHA256()
	if err != nil || !isLowerHex(want, 64) {
		t.Fatal("canonical cancellation digest unavailable")
	}
	// Golden version-1 Go encoding. This is not a public wire contract.
	if want != "f859b2092c5bc2bfae0a0906752eb6d35bd1b5c03a38f1b8ac4e7d15b0c011bc" {
		t.Fatal("canonical cancellation v1 encoding changed")
	}
	other := c
	other.OperationID = "55555555-5555-4555-8555-555555555555"
	other.Task.ExpiresAt = time.Now().Add(time.Hour)
	other.Task.DeliveryCount = 30
	if got, _ := other.canonicalPayloadSHA256(); got != want {
		t.Fatal("operation key or mutable delivery metadata altered logical digest")
	}
	for name, change := range map[string]func(*cancellationRequest){
		"run":     func(c *cancellationRequest) { c.Task.RunID = other.OperationID },
		"task":    func(c *cancellationRequest) { c.Task.TaskID = other.OperationID },
		"attempt": func(c *cancellationRequest) { c.Task.AttemptID = other.OperationID },
		"stage":   func(c *cancellationRequest) { c.Task.Stage = "PLANNING" },
		"owner":   func(c *cancellationRequest) { c.Task.ClaimantID += "-other" },
		"epoch":   func(c *cancellationRequest) { c.Task.LeaseEpoch++ },
		"version": func(c *cancellationRequest) { c.ExpectedRunVersion++ },
		"policy":  func(c *cancellationRequest) { c.ExpectedPolicyEpoch++ },
		"source":  func(c *cancellationRequest) { c.ExpectedSourceFingerprintSHA256 = strings.Repeat("c", 64) },
		"scope":   func(c *cancellationRequest) { c.SyntheticPrefix += "b" },
	} {
		t.Run(name, func(t *testing.T) {
			x := c
			change(&x)
			if got, _ := x.canonicalPayloadSHA256(); got == want {
				t.Fatal("semantic control identity omitted from digest")
			}
		})
	}
}

func TestCancellationIdentityValidation(t *testing.T) {
	c := canonicalCancellationFixture()
	if c.validate() != nil {
		t.Fatal("valid private synthetic cancellation rejected")
	}
	for name, change := range map[string]func(*cancellationRequest){
		"operation": func(c *cancellationRequest) { c.OperationID = "not-a-uuid" },
		"run":       func(c *cancellationRequest) { c.Task.RunID = "" },
		"task":      func(c *cancellationRequest) { c.Task.TaskID = "" },
		"attempt":   func(c *cancellationRequest) { c.Task.AttemptID = "" },
		"stage":     func(c *cancellationRequest) { c.Task.Stage = "CANCELLED" },
		"epoch":     func(c *cancellationRequest) { c.Task.LeaseEpoch = -1 },
		"owner":     func(c *cancellationRequest) { c.Task.ClaimantID = " " },
		"encoding":  func(c *cancellationRequest) { c.Task.ClaimantID += string([]byte{0xff}) },
		"queued":    func(c *cancellationRequest) { c.Task.LeaseEpoch = 0 },
		"version":   func(c *cancellationRequest) { c.ExpectedRunVersion = 0 },
		"policy":    func(c *cancellationRequest) { c.ExpectedPolicyEpoch = 0 },
		"source":    func(c *cancellationRequest) { c.ExpectedSourceFingerprintSHA256 = "bad" },
		"scope":     func(c *cancellationRequest) { c.SyntheticPrefix = "production" },
	} {
		t.Run(name, func(t *testing.T) {
			x := c
			change(&x)
			// Invalid input must fail before trying any database access.
			if _, err := (&Store{}).requestCancellation(t.Context(), x); err != errCancellationRejected {
				t.Fatal("invalid synthetic control reached database boundary")
			}
		})
	}
}
