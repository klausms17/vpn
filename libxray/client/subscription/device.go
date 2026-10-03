// Package subscription adds and refreshes subscriptions as the Android
// app's SubscriptionUpdater does: it downloads a panel's list with the
// device headers, keeps the ids of servers that are still there and
// replaces the servers only with a real new list.
package subscription

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
)

// hwidSalt is part of the device id the panel counts. Changing it would
// make every device a new one: never change it.
const hwidSalt = "klausvpn-hwid-v1|"

// modelMax bounds the device model sent to the panel.
const modelMax = 64

// Device is what a subscription request tells the panel about this device,
// for Remnawave's device limit. It goes with subscription requests only.
type Device struct {
	HWID      string `json:"X-Hwid"`
	OS        string `json:"X-Device-Os"`
	OSVersion string `json:"X-Ver-Os"`
	Model     string `json:"X-Device-Model"`
}

// Headers returns the headers as the JSON object FetchWithHeaders takes.
func (d Device) Headers() string {
	out, _ := json.Marshal(d)
	return string(out)
}

// HashID is the device id the panel sees: the first 32 hex digits of
// SHA-256 of the salted id, so the raw id never leaves the device (the
// Android app's recipe, on ANDROID_ID there and MachineGuid on Windows).
func HashID(id string) string {
	sum := sha256.Sum256([]byte(hwidSalt + id))
	return hex.EncodeToString(sum[:])[:32]
}

// Model is "<manufacturer> <model>" without repeating the brand, in
// printable ASCII.
func Model(manufacturer, model string) string {
	brand := strings.TrimSpace(manufacturer)
	name := strings.TrimSpace(model)
	full := brand + " " + name
	if brand == "" || strings.HasPrefix(strings.ToLower(name), strings.ToLower(brand)) {
		full = name
	}
	return Printable(full, modelMax)
}

// Printable keeps printable ASCII with single spaces, at most max
// characters: header values must not carry control characters.
func Printable(text string, max int) string {
	kept := strings.Map(func(r rune) rune {
		if r < ' ' || r > '~' {
			return -1
		}
		return r
	}, text)
	joined := strings.Join(strings.Fields(kept), " ")
	if len(joined) > max {
		joined = joined[:max]
	}
	return strings.TrimSpace(joined)
}
