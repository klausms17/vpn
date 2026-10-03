package tunnel

import (
	"testing"
	"time"
)

func always(v bool) func() bool { return func() bool { return v } }

func notAsked(t *testing.T) func() bool {
	return func() bool {
		t.Helper()
		t.Error("shouldRun read where it cannot matter")
		return true
	}
}

func check(t *testing.T, f StartFailure, shouldRun func() bool, want FailureAction) {
	t.Helper()
	if got := Decide(f, shouldRun); got != want {
		t.Errorf("%+v: %d, want %d", f, got, want)
	}
}

func TestNewSettingsThatFailBeforeTheSwapKeepTheOldTunnelAndNeverGiveUp(t *testing.T) {
	for _, user := range []bool{true, false} {
		for attempt := range MaxStartRetries {
			check(t, StartFailure{Restarting: true, SessionAlive: true, UserRequested: user, Attempt: attempt}, notAsked(t), KeepOldAndRetry)
		}
		check(t, StartFailure{Restarting: true, SessionAlive: true, UserRequested: user, Attempt: MaxStartRetries}, notAsked(t), KeepOld)
	}
}

func TestAfterTheSwapARestartRetriesTwice(t *testing.T) {
	for attempt := range MaxStartRetries {
		check(t, StartFailure{Restarting: true, Swapped: true, SessionAlive: true, UserRequested: true, Attempt: attempt}, always(true), HoldAndRetry)
	}
	check(t, StartFailure{Restarting: true, Swapped: true, SessionAlive: true, UserRequested: true, Attempt: MaxStartRetries}, notAsked(t), GiveUp)
}

func TestAHaltedCoreIsNoOldTunnelToKeep(t *testing.T) {
	// An earlier failure stopped the core: a restart, retried.
	check(t, StartFailure{Restarting: true, UserRequested: true}, always(true), HoldAndRetry)
	check(t, StartFailure{Restarting: true, Attempt: MaxStartRetries}, notAsked(t), GiveUp)
}

func TestAFreshStartTheUserAskedForGivesUpAtOnce(t *testing.T) {
	check(t, StartFailure{UserRequested: true}, notAsked(t), GiveUp)
}

func TestAFreshStartNobodyAskedForIsTriedTwiceMore(t *testing.T) {
	check(t, StartFailure{Attempt: 0}, always(true), HoldAndRetry)
	check(t, StartFailure{Attempt: 1}, always(true), HoldAndRetry)
	check(t, StartFailure{Attempt: 2}, notAsked(t), GiveUp)
}

func TestATunnelTurnedOffMeanwhileIsNotRetried(t *testing.T) {
	check(t, StartFailure{Restarting: true, Swapped: true}, always(false), GiveUp)
	check(t, StartFailure{}, always(false), GiveUp)
}

func TestAnotherVPNAlwaysMeansStayingOff(t *testing.T) {
	for _, restarting := range []bool{true, false} {
		for _, swapped := range []bool{true, false} {
			for _, user := range []bool{true, false} {
				for attempt := 0; attempt <= MaxStartRetries; attempt++ {
					f := StartFailure{AnotherVPN: true, Restarting: restarting, Swapped: swapped, SessionAlive: restarting, UserRequested: user, Attempt: attempt}
					check(t, f, notAsked(t), StayOff)
				}
			}
		}
	}
}

func TestTheFirstRetryComesQuicklyTheSecondLater(t *testing.T) {
	if RetryDelay(1) != 1500*time.Millisecond || RetryDelay(2) != 5*time.Second {
		t.Errorf("delays %v %v", RetryDelay(1), RetryDelay(2))
	}
}
