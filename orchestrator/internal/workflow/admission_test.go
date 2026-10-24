package workflow

import (
	"strings"
	"testing"
)

func TestCanonicalAdmissionDigestVersionedAndFieldBound(t *testing.T) {
	base := Admission{
		CommandID:  "00000000-0000-4000-8000-000000000001",
		SubjectKey: "synthetic:vn-t10-it-00000000000000000000000000000001:test",
		ActorID:    "owner", JiraSiteID: "site", JiraProjectID: "project",
		JiraIssueID: "issue", GitLabProjectID: "gitlab",
		RepositoryBaseSHA: strings.Repeat("0", 40), TargetBranch: "main",
		ObservedTargetHeadSHA:   strings.Repeat("1", 40),
		SourceFingerprintSHA256: strings.Repeat("2", 64),
		PolicyID:                "policy", PolicyEpoch: 1, BudgetLimit: 3,
	}
	a, err := base.canonicalPayloadSHA256()
	if err != nil || !isLowerHex(a, 64) {
		t.Fatalf("invalid digest: %q %v", a, err)
	}
	b, err := base.canonicalPayloadSHA256()
	if err != nil || a != b {
		t.Fatal("digest is not deterministic")
	}
	changed := base
	changed.CommandID = "00000000-0000-4000-8000-000000000002"
	c, err := changed.canonicalPayloadSHA256()
	if err != nil || c != a {
		t.Fatal("command id should be an idempotency key, not a body field")
	}
	variants := []Admission{}
	changed = base
	changed.ActorID = "other"
	variants = append(variants, changed)
	changed = base
	changed.PolicyEpoch++
	variants = append(variants, changed)
	changed = base
	changed.SourceFingerprintSHA256 = strings.Repeat("3", 64)
	variants = append(variants, changed)
	changed = base
	changed.GitLabProjectID = "other"
	variants = append(variants, changed)
	changed = base
	changed.SubjectKey += ":altered"
	variants = append(variants, changed)
	changed = base
	changed.BudgetLimit++
	variants = append(variants, changed)
	for i, item := range variants {
		got, err := item.canonicalPayloadSHA256()
		if err != nil || got == a {
			t.Fatalf("body field %d not bound to digest: %v", i, err)
		}
	}
}
