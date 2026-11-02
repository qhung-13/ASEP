package workflow

import (
	"strings"
	"testing"
)

func TestDeliveryRequestCrossLanguageGolden(t *testing.T) {
	id := func(n string) string { return "00000000-0000-0000-0000-00000000000" + n }
	r := DeliveryRequest{id("1"), id("2"), id("3"), 9, id("4"), "local-fixture", 1, strings.Repeat("a", 40), strings.Repeat("a", 40), strings.Repeat("b", 40), strings.Repeat("c", 40), strings.Repeat("d", 64), id("5"), id("6"), strings.Repeat("e", 64), strings.Repeat("f", 64)}
	hash, err := r.Hash()
	if err != nil || hash != "679f2b93f889c6e966afa4c721fe9fd468c0d6533bdd542462266ee399ffdea6" {
		t.Fatal("Java request v1 codec drift")
	}
	changed := r
	changed.CandidateCommitSHA = strings.Repeat("9", 40)
	other, err := changed.Hash()
	if err != nil || other == hash {
		t.Fatal("candidate not bound")
	}
	changed = r
	changed.VerificationReceiptID = changed.SealReceiptID
	if _, err := changed.Hash(); err == nil {
		t.Fatal("identical receipts accepted")
	}
}
