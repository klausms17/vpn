package tunnel

import "time"

// FailureAction is what the tunnel does after a start failed (see Decide).
type FailureAction int

const (
	// StayOff: another program's VPN is in place. Stay off, without an
	// error.
	StayOff FailureAction = iota
	// KeepOld: the new tunnel never replaced the running one, which keeps
	// carrying the traffic.
	KeepOld
	// KeepOldAndRetry: as KeepOld, and the start is tried once more later.
	KeepOldAndRetry
	// HoldAndRetry: stop the core and start again a little later. Android
	// keeps its interface meanwhile, so apps wait instead of going around
	// the VPN.
	HoldAndRetry
	// GiveUp: stop the tunnel and show the error.
	GiveUp
)

// MaxStartRetries is how many more times a failed start of a tunnel that
// should run is tried.
const MaxStartRetries = 2

// StartFailure describes a failed start.
type StartFailure struct {
	// AnotherVPN: an automatic start found another program's VPN in place.
	AnotherVPN bool
	// Restarting: a tunnel ran, or a failed start waits for its retry.
	Restarting bool
	// Swapped: the new tunnel had already replaced the old one.
	Swapped bool
	// SessionAlive: the old core still runs.
	SessionAlive bool
	// UserRequested: the user asked for this start just now.
	UserRequested bool
	// Attempt is 0 for the first try.
	Attempt int
}

// Decide says what a failed start does next, as Android's
// StartFailurePolicy. shouldRun is read only when the answer depends on it.
func Decide(f StartFailure, shouldRun func() bool) FailureAction {
	switch {
	case f.AnotherVPN:
		// The user switched to another VPN while this one was down.
		return StayOff
	case f.Restarting && !f.Swapped && f.SessionAlive:
		// New settings or another server for a tunnel that works: until
		// the new one replaced it, the old tunnel still carries the traffic.
		if f.Attempt < MaxStartRetries {
			return KeepOldAndRetry
		}
		return KeepOld
	case (f.Restarting || !f.UserRequested) && f.Attempt < MaxStartRetries && shouldRun():
		// It was running, or should be running (a restart nobody asked
		// for): a second try usually works.
		return HoldAndRetry
	default:
		return GiveUp
	}
}

// RetryDelay is the pause before retry number attempt (1 or 2).
func RetryDelay(attempt int) time.Duration {
	if attempt == 1 {
		return 1500 * time.Millisecond
	}
	return 5 * time.Second
}
