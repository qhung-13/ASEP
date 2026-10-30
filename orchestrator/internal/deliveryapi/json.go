package deliveryapi

import (
	"bytes"
	"encoding/json"
	"io"
	"strings"
)

// Reject duplicate (including case-variant) keys before Go's case-insensitive
// struct decoding. A bounded peer cannot hide a second identity/policy value.
func strictJSON(body []byte, value any) bool {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	var scan func(int) bool
	scan = func(depth int) bool {
		if depth > 32 {
			return false
		}
		token, err := decoder.Token()
		if err != nil {
			return false
		}
		delimiter, ok := token.(json.Delim)
		if !ok {
			return true
		}
		switch delimiter {
		case '{':
			seen := map[string]bool{}
			for decoder.More() {
				key, err := decoder.Token()
				name, ok := key.(string)
				if err != nil || !ok {
					return false
				}
				name = strings.ToLower(name)
				if seen[name] {
					return false
				}
				seen[name] = true
				if !scan(depth + 1) {
					return false
				}
			}
			end, err := decoder.Token()
			return err == nil && end == json.Delim('}')
		case '[':
			for decoder.More() {
				if !scan(depth + 1) {
					return false
				}
			}
			end, err := decoder.Token()
			return err == nil && end == json.Delim(']')
		}
		return false
	}
	if !scan(0) {
		return false
	}
	if _, err := decoder.Token(); err != io.EOF {
		return false
	}
	decoder = json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	return decoder.Decode(value) == nil && decoder.Decode(new(any)) == io.EOF
}
