package service

import (
	"sync"
	"time"
)

// holdBackstop ends a traffic hold that the engine has not ended by then.
// The engine ends each hold within its own limit (20 s); should it hang
// mid-restart, the PC must still not stay offline because of the service.
const holdBackstop = 25 * time.Second

// boundedHold is the engine's Hold over on and off (WireGuard's WFP rules
// on Windows), with holdBackstop's guarantee.
type boundedHold struct {
	on       func() error
	off      func()
	backstop time.Duration
	log      func(string)

	mu    sync.Mutex
	timer *time.Timer
}

func (h *boundedHold) On() error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if err := h.on(); err != nil {
		return err
	}
	var t *time.Timer
	t = time.AfterFunc(h.backstop, func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		// An earlier hold's timer ends nothing.
		if h.timer == t {
			h.timer = nil
			h.off()
			h.log("the traffic hold outlasted its limit: let through")
		}
	})
	h.timer = t
	return nil
}

func (h *boundedHold) Off() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.timer != nil {
		h.timer.Stop()
		h.timer = nil
	}
	h.off()
}
