package account

import "time"

// State is what an app keeps about its account. The session token is its
// only secret; the password is never kept.
type State struct {
	Email  string `json:"email,omitempty"`
	Token  string `json:"token,omitempty"`
	Status Status `json:"status,omitempty"`
	// SubscriptionID is the app's subscription made from the account's
	// link.
	SubscriptionID string `json:"subscriptionId,omitempty"`
	// CheckedAt is when the service last answered about the account (Unix
	// ms).
	CheckedAt int64 `json:"checkedAt,omitempty"`
}

// How often an app asks the service about its account: often while the
// owner has not decided yet, so that access comes soon after.
const (
	PendingEvery = 5 * time.Minute
	CheckEvery   = time.Hour
)

// SignedIn tells whether the app holds a session.
func (s State) SignedIn() bool { return s.Token != "" }

// Due tells whether it is time to ask the service again: often while the
// owner has not decided, or while the access granted has no subscription
// here yet (its download failed).
func (s State) Due(now int64) bool {
	if !s.SignedIn() {
		return false
	}
	every := CheckEvery
	if s.Status == Pending || s.Status == Active && s.SubscriptionID == "" {
		every = PendingEvery
	}
	return now-s.CheckedAt >= every.Milliseconds() || now < s.CheckedAt
}

// With is the state after the service answered about the account.
func (s State) With(a Account, now int64) State {
	s.Email, s.Status, s.CheckedAt = a.Email, a.Status, now
	return s
}
