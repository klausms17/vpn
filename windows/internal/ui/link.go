// Package ui is the tray icon and the window. It holds no keys and does no
// networking: everything goes to the service over the pipe.
package ui

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"time"

	"github.com/klausms17/vpn/windows/internal/ipc"
)

// Snapshot is everything the window shows, as the service last told it.
type Snapshot struct {
	// Service: the window is connected to the service.
	Service bool `json:"service"`
	// Outdated: the service speaks another version (an update replaced
	// it); the window must be restarted.
	Outdated bool         `json:"outdated"`
	Status   ipc.Status   `json:"status"`
	Profiles ipc.Profiles `json:"profiles"`
	// Seq orders the snapshots: the page ignores one older than what it
	// shows (an answer to Snapshot that crossed an event).
	Seq uint64 `json:"seq"`
}

var (
	errNoService = errors.New("Служба Kirov VPN не запущена. Перезагрузите компьютер или переустановите Kirov VPN.")
	errOutdated  = errors.New("Kirov VPN обновился: закройте его в трее («Выход») и откройте снова.")
)

// link keeps the window connected to the service and the snapshot current.
type link struct {
	// onChange gets every new snapshot, one at a time and in order.
	onChange func(Snapshot)

	// nmu keeps the notifications in the order of the changes.
	nmu    sync.Mutex
	mu     sync.Mutex
	client *ipc.Client
	snap   Snapshot
}

func newLink(onChange func(Snapshot)) *link {
	return &link{onChange: onChange, snap: Snapshot{Profiles: ipc.Profiles{Profiles: []ipc.Profile{}}}}
}

// Dialer opens a connection to the service.
type Dialer func(ctx context.Context) (io.ReadWriteCloser, error)

// run connects to the service, and connects again whenever the connection
// ends (the service restarts with an update or after a crash), until ctx
// ends.
func (l *link) run(ctx context.Context, dial Dialer) {
	const maxWait = 5 * time.Second
	wait := 500 * time.Millisecond
	for ctx.Err() == nil {
		dctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		conn, err := dial(dctx)
		cancel()
		if err != nil {
			l.update(func(s *Snapshot) { s.Service = false })
			select {
			case <-time.After(wait):
			case <-ctx.Done():
			}
			wait = min(wait*2, maxWait)
			continue
		}
		wait = 500 * time.Millisecond
		c := ipc.NewClient(conn, l.onEvent)
		l.setClient(c)
		l.update(func(s *Snapshot) { s.Service = true })
		select {
		case <-c.Done():
		case <-ctx.Done():
			c.Close()
		}
		l.setClient(nil)
		l.update(func(s *Snapshot) { s.Service = false })
	}
}

func (l *link) setClient(c *ipc.Client) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.client = c
}

func (l *link) snapshot() Snapshot {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.snap
}

func (l *link) update(change func(*Snapshot)) {
	l.nmu.Lock()
	defer l.nmu.Unlock()
	l.mu.Lock()
	before := l.snap
	change(&l.snap)
	changed := !equal(before, l.snap)
	if changed {
		l.snap.Seq++
	}
	after := l.snap
	l.mu.Unlock()
	if changed {
		l.onChange(after)
	}
}

func (l *link) onEvent(ev ipc.Event) {
	switch ev.Event {
	case ipc.EventHello:
		var h ipc.Hello
		if json.Unmarshal(ev.Data, &h) == nil {
			l.update(func(s *Snapshot) { s.Outdated = h.Version != ipc.Version })
		}
	case ipc.EventStatus:
		var st ipc.Status
		if json.Unmarshal(ev.Data, &st) == nil {
			l.update(func(s *Snapshot) { s.Status = st })
		}
	case ipc.EventProfiles:
		var p ipc.Profiles
		if json.Unmarshal(ev.Data, &p) == nil {
			if p.Profiles == nil {
				p.Profiles = []ipc.Profile{}
			}
			l.update(func(s *Snapshot) { s.Profiles = p })
		}
	}
}

// call runs op on the service.
func (l *link) call(ctx context.Context, op string, args, result any) error {
	l.mu.Lock()
	c, outdated := l.client, l.snap.Outdated
	l.mu.Unlock()
	switch {
	case c == nil:
		return errNoService
	case outdated:
		return errOutdated
	}
	err := c.Call(ctx, op, args, result)
	if errors.Is(err, ipc.ErrClosed) {
		return errNoService
	}
	return err
}

func equal(a, b Snapshot) bool {
	ja, _ := json.Marshal(a)
	jb, _ := json.Marshal(b)
	return string(ja) == string(jb)
}
