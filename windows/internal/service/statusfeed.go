package service

import (
	"sync"
	"sync/atomic"

	"github.com/klausms17/vpn/windows/internal/ipc"
)

// statusFeed is the status as the windows get it: the engine's, and while
// the tunnel is connected, the warning that another VPN takes its traffic.
type statusFeed struct {
	send func(ipc.Status)

	// mu keeps the statuses sent in the order they were made.
	mu      sync.Mutex
	engine  ipc.Status
	warning string
	// shown is read without the lock: the pipe's greeting reads it under
	// the server's lock, which send takes.
	shown atomic.Pointer[ipc.Status]
}

func newStatusFeed(send func(ipc.Status)) *statusFeed {
	f := &statusFeed{send: send, engine: ipc.Status{State: ipc.Disconnected}}
	f.shown.Store(&ipc.Status{State: ipc.Disconnected})
	return f
}

// fromEngine takes the engine's status (engine.Deps.Publish).
func (f *statusFeed) fromEngine(s ipc.Status) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.engine = s
	f.publish()
}

// warn sets the warning; "" clears it.
func (f *statusFeed) warn(warning string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if warning == f.warning {
		return
	}
	f.warning = warning
	if f.engine.State == ipc.Connected {
		f.publish()
	}
}

func (f *statusFeed) publish() {
	s := f.engine
	if s.State == ipc.Connected {
		s.Warning = f.warning
	}
	f.shown.Store(&s)
	f.send(s)
}

// Status is the status the windows were sent last.
func (f *statusFeed) Status() ipc.Status { return *f.shown.Load() }

// shownTunnel is the tunnel as the windows see it: its status carries the
// feed's warning.
type shownTunnel struct {
	Tunnel
	feed *statusFeed
}

func (t shownTunnel) Status() ipc.Status { return t.feed.Status() }
