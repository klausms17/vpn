package service

import (
	"github.com/klausms17/vpn/libxray/client/store"
)

// runtimeState is Android's "vpn_runtime" preferences, so far only
// should_run: whether the tunnel comes back when the service starts.
type runtimeState struct {
	ShouldRun bool `json:"shouldRun"`
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
