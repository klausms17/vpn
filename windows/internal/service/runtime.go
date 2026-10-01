package service

import (
	"time"

	"github.com/klausms17/vpn/libxray/client/store"
)

// runtimeState is Android's "vpn_runtime" preferences: should_run, whether
// the tunnel comes back when the service starts, and the boot the service
// saw last.
type runtimeState struct {
	ShouldRun bool `json:"shouldRun"`
	// Boot is when Windows started (Unix seconds), as the service saw it
	// when it last started: a start within the same boot is a restart.
	Boot int64 `json:"boot,omitempty"`
}

// runtimeStore keeps runtimeState in a file (engine.Runtime).
type runtimeStore struct {
	s   *store.Store[runtimeState]
	log func(string)
}

func (r *runtimeStore) ShouldRun() bool { return r.s.Read().ShouldRun }

func (r *runtimeStore) SetShouldRun(v bool) {
	if _, err := r.s.Update(func(st runtimeState) runtimeState {
		st.ShouldRun = v
		return st
	}); err != nil {
		r.log("runtime state not saved: " + err.Error())
	}
}

// firstStartSince records boot as seen and tells whether the service starts
// for the first time since then: the boot time it reckons from the uptime
// drifts by a moment from start to start.
func (r *runtimeStore) firstStartSince(boot time.Time) bool {
	first := true
	if _, err := r.s.Update(func(st runtimeState) runtimeState {
		first = st.Boot == 0 || max(st.Boot-boot.Unix(), boot.Unix()-st.Boot) > 60
		st.Boot = boot.Unix()
		return st
	}); err != nil {
		r.log("runtime state not saved: " + err.Error())
	}
	return first
}
