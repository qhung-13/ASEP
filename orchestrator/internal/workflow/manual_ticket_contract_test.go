package workflow

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"strings"
	"testing"
)

// Shared with the Java normalizer/coordinator tests. This is fixture-v1, not a
// production Jira revision or an authenticated network-admission protocol.
type manualTicketVector struct {
	ResponseBody           string    `json:"responseBody"`
	NormalizedDescription  string    `json:"normalizedDescription"`
	Command                Admission `json:"command"`
	CanonicalPayloadSHA256 string    `json:"canonicalPayloadSha256"`
}

func readManualTicketVector(t *testing.T) manualTicketVector {
	t.Helper()
	data, err := os.ReadFile("../../../backend/src/test/resources/contracts/manual-jira-fixture-v1.json")
	if err != nil {
		t.Fatal("shared Java/Go fixture unavailable")
	}
	var v manualTicketVector
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.DisallowUnknownFields()
	if err := d.Decode(&v); err != nil {
		t.Fatal("shared Java/Go fixture invalid", err)
	}
	return v
}

func manualTicketFingerprint(c Admission, raw []byte, description string) string {
	var response struct {
		Fields struct {
			Summary   string                `json:"summary"`
			IssueType struct{ Name string } `json:"issuetype"`
			Status    struct{ Name string } `json:"status"`
		} `json:"fields"`
	}
	if json.Unmarshal(raw, &response) != nil {
		return ""
	}
	hash := func(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
	h := sha256.New()
	for _, value := range []string{"asep-jira-fixture-v1", c.JiraSiteID, c.JiraProjectID, c.JiraIssueID,
		response.Fields.IssueType.Name, response.Fields.Status.Name, hash(raw),
		hash([]byte(response.Fields.Summary)), hash([]byte(description))} {
		var size [4]byte
		binary.BigEndian.PutUint32(size[:], uint32(len([]byte(value))))
		h.Write(size[:])
		h.Write([]byte(value))
	}
	return hex.EncodeToString(h.Sum(nil))
}

func TestManualTicketJavaGoContractVector(t *testing.T) {
	v := readManualTicketVector(t)
	if err := v.Command.validate(); err != nil {
		t.Fatal("Java command does not satisfy existing Go admission", err)
	}
	if v.Command.SourceFingerprintSHA256 != manualTicketFingerprint(v.Command, []byte(v.ResponseBody), v.NormalizedDescription) {
		t.Fatal("Java/Go synthetic snapshot fingerprint differs")
	}
	digest, err := v.Command.canonicalPayloadSHA256()
	if err != nil || digest != v.CanonicalPayloadSHA256 {
		t.Fatal("Java fixture differs from existing Go v1 canonical admission hash", err)
	}
	changed := v.Command
	changed.SourceFingerprintSHA256 = strings.Repeat("a", 64)
	other, err := changed.canonicalPayloadSHA256()
	if err != nil || digest == other {
		t.Fatal("ticket snapshot revision/content is not bound into admission identity")
	}
}
