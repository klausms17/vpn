// Package model holds the saved servers and subscriptions, in the JSON of
// the Android app's Models.kt, and the changes made to them. The Windows
// service uses it, and the iPhone app will.
package model

import (
	"encoding/json"
	"unicode/utf8"
)

// StoredProfile is one server. Outbounds is the Xray outbound list the
// core made from the key; [0] is the proxy.
type StoredProfile struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Protocol string `json:"protocol"`
	Address  string `json:"address"`
	Port     int    `json:"port"`
	Network  string `json:"network"`
	Security string `json:"security"`
	// Link is the original share link, kept so it can be copied again.
	Link           string          `json:"link,omitempty"`
	Outbounds      json.RawMessage `json:"outbounds"`
	SubscriptionID string          `json:"subscriptionId,omitempty"`
	CreatedAt      int64           `json:"createdAt"`
}

// Subscription is a panel link whose servers the app keeps up to date.
type Subscription struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	URL  string `json:"url"`
	// UpdatedAt is when its servers were last replaced by a fresh list.
	UpdatedAt int64 `json:"updatedAt"`
	// UserInfo is the raw "subscription-userinfo" header (traffic, expiry).
	UserInfo string `json:"userInfo,omitempty"`
	// LastError says why the last attempt failed; empty once one succeeds.
	LastError     string `json:"lastError,omitempty"`
	LastAttemptAt int64  `json:"lastAttemptAt"`
	// Notice is what the panel said instead of, or besides, servers (device
	// limit, expired subscription); the servers from before stay then.
	Notice string `json:"notice,omitempty"`
	// Announce is the owner's message ("announce" header).
	Announce string `json:"announce,omitempty"`
	// SupportURL, ReportURL and AppURL come from the "support-url",
	// "klaus-report-url" and "klaus-app-url" headers (the last two https only).
	SupportURL string `json:"supportUrl,omitempty"`
	ReportURL  string `json:"reportUrl,omitempty"`
	AppURL     string `json:"appUrl,omitempty"`
}

// ProfilesState is everything saved about servers.
type ProfilesState struct {
	Profiles      []StoredProfile `json:"profiles"`
	Subscriptions []Subscription  `json:"subscriptions"`
	SelectedID    string          `json:"selectedId,omitempty"`
}

// MarshalJSON writes empty lists as [], as Android does, never as null.
func (s ProfilesState) MarshalJSON() ([]byte, error) {
	type plain ProfilesState
	if s.Profiles == nil {
		s.Profiles = []StoredProfile{}
	}
	if s.Subscriptions == nil {
		s.Subscriptions = []Subscription{}
	}
	return json.Marshal(plain(s))
}

// Selected returns the selected server, if it still exists.
func (s ProfilesState) Selected() (StoredProfile, bool) {
	for _, p := range s.Profiles {
		if p.ID == s.SelectedID {
			return p, true
		}
	}
	return StoredProfile{}, false
}

// Key is a parsed server ready to be saved: the core's Profile once its
// certificate, if it needed one, is pinned.
type Key struct {
	Name      string
	Protocol  string
	Address   string
	Port      int
	Network   string
	Security  string
	Link      string
	Outbounds json.RawMessage
}

// Stored makes a saved server of k. An unnamed key is named after its
// address.
func (k Key) Stored(id, subscriptionID string, createdAt int64) StoredProfile {
	return StoredProfile{
		ID: id, Name: k.Label(), Protocol: k.Protocol, Address: k.Address, Port: k.Port,
		Network: k.Network, Security: k.Security, Link: k.Link, Outbounds: k.Outbounds,
		SubscriptionID: subscriptionID, CreatedAt: createdAt,
	}
}

// Label is the name k is shown and saved under: its own, or else its
// address, at most MaxName characters.
func (k Key) Label() string {
	if k.Name != "" {
		return ClipName(k.Name)
	}
	return ClipName(k.Address)
}

// MaxName bounds a server's name, which comes from whoever wrote the key.
const MaxName = 100

// ClipName cuts name to MaxName characters.
func ClipName(name string) string {
	if utf8.RuneCountInString(name) <= MaxName {
		return name
	}
	return string([]rune(name)[:MaxName-1]) + "…"
}
