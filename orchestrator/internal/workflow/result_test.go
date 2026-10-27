package workflow

import (
	"strings"
	"testing"
	"time"
)

func canonicalResultFixture() investigationAdvance {
	return investigationAdvance{
		OperationID: "11111111-1111-4111-8111-111111111111",
		Lease: TaskLease{RunID: "22222222-2222-4222-8222-222222222222",
			TaskID: "33333333-3333-4333-8333-333333333333", AttemptID: "44444444-4444-4444-8444-444444444444",
			Stage: "INVESTIGATING", ClaimantID: "synthetic-worker", LeaseEpoch: 1},
		ExpectedRunVersion: 1, ExpectedPolicyEpoch: 1,
		ExpectedSourceFingerprintSHA256: strings.Repeat("b", 64),
		EvidenceRef:                     "synthetic:vn-t10-it-canonical:trusted-evidence",
	}
}

func TestResultCanonicalPayload(t *testing.T) {
	c := canonicalResultFixture()
	want, err := c.canonicalPayloadSHA256()
	if err != nil || !isLowerHex(want, 64) {
		t.Fatal("canonical digest unavailable")
	}
	// Golden v1 fixture fixes field order, semantics discriminator and version;
	// changing the format requires an explicit new digest-version contract.
	if want != "797454d87ad9c311dccf055dc0bccd5c3fc04cfa6cbc315c98b9cf9a6affec49" {
		t.Fatal("canonical result v1 encoding changed")
	}
	transport := c
	transport.Lease.ExpiresAt = time.Now().Add(time.Minute)
	transport.Lease.DeliveryCount = 20
	transport.OperationID = "55555555-5555-4555-8555-555555555555"
	if got, _ := transport.canonicalPayloadSHA256(); got != want {
		t.Fatal("mutable metadata changed result digest")
	}
	changes := map[string]func(*investigationAdvance){
		"run":      func(c *investigationAdvance) { c.Lease.RunID = transport.OperationID },
		"task":     func(c *investigationAdvance) { c.Lease.TaskID = transport.OperationID },
		"attempt":  func(c *investigationAdvance) { c.Lease.AttemptID = transport.OperationID },
		"stage":    func(c *investigationAdvance) { c.Lease.Stage = "PLANNING" },
		"claimant": func(c *investigationAdvance) { c.Lease.ClaimantID += "-other" },
		"epoch":    func(c *investigationAdvance) { c.Lease.LeaseEpoch++ },
		"version":  func(c *investigationAdvance) { c.ExpectedRunVersion++ },
		"policy":   func(c *investigationAdvance) { c.ExpectedPolicyEpoch++ },
		"source":   func(c *investigationAdvance) { c.ExpectedSourceFingerprintSHA256 = strings.Repeat("c", 64) },
		"evidence": func(c *investigationAdvance) { c.EvidenceRef += "-other" },
		"budget":   func(c *investigationAdvance) { c.BudgetDebit++ },
	}
	for name, change := range changes {
		t.Run(name, func(t *testing.T) {
			other := c
			change(&other)
			if got, _ := other.canonicalPayloadSHA256(); got == want {
				t.Fatal("semantic identity missing from digest")
			}
		})
	}
}

func TestResultIdentityValidation(t *testing.T) {
	c := canonicalResultFixture()
	if err := c.validate(); err != nil {
		t.Fatal("valid synthetic result rejected")
	}
	for _, bad := range []string{"", "not-a-uuid", strings.ToUpper("abcdefab-abcd-4abc-8abc-abcdefabcdef")} {
		c.OperationID = bad
		if c.validate() == nil {
			t.Fatal("noncanonical operation identity accepted")
		}
	}
	c = canonicalResultFixture()
	c.EvidenceRef += string([]byte{0xff})
	if c.validate() == nil {
		t.Fatal("invalid UTF-8 could alias another canonical result")
	}
}
