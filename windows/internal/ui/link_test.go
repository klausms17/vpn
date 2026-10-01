package ui

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/klausms17/vpn/windows/internal/ipc"
)

// fakeService answers like the real one, over in-memory pipes.
type fakeService struct {
	server *ipc.Server
	mu     sync.Mutex
	up     bool
	conns  []net.Conn
	status ipc.Status
	calls  []string
}

func newFakeService() *fakeService {
	f := &fakeService{up: true, status: ipc.Status{State: ipc.Disconnected}}
	f.server = ipc.NewServer(func(_ context.Context, op string, args json.RawMessage) (any, error) {
		f.mu.Lock()
		f.calls = append(f.calls, op)
		f.mu.Unlock()
		switch op {
		case ipc.OpConnect:
			f.setStatus(ipc.Status{State: ipc.Connected, ProfileName: "Германия"})
			return nil, nil
		case ipc.OpImport:
			return ipc.ImportResult{Added: 1, Message: "Добавлено серверов: 1"}, nil
		}
		return nil, errors.New("Не выбран сервер")
	}, func() []ipc.Event {
		return []ipc.Event{
			ipc.NewEvent(ipc.EventStatus, f.status),
			ipc.NewEvent(ipc.EventProfiles, ipc.Profiles{Profiles: []ipc.Profile{{ID: "de", Name: "Германия"}}, SelectedID: "de"}),
		}
	}, func(string) {})
	return f
}

func (f *fakeService) setStatus(s ipc.Status) {
	f.status = s
	f.server.Broadcast(ipc.NewEvent(ipc.EventStatus, s))
}

func (f *fakeService) dial(ctx context.Context) (io.ReadWriteCloser, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.up {
		return nil, errors.New("pipe not found")
	}
	a, b := net.Pipe()
	f.conns = append(f.conns, a)
	go f.server.ServeConn(a)
	return b, nil
}

// restart drops every connection; the service is back after a moment.
func (f *fakeService) restart() {
	f.mu.Lock()
	f.up = false
	for _, c := range f.conns {
		c.Close()
	}
	f.conns = nil
	f.mu.Unlock()
	time.AfterFunc(200*time.Millisecond, func() {
		f.mu.Lock()
		f.up = true
		f.mu.Unlock()
	})
}

type snapshots struct {
	mu   sync.Mutex
	list []Snapshot
}

func (s *snapshots) add(snap Snapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.list = append(s.list, snap)
}

func (s *snapshots) waitFor(t *testing.T, ok func(Snapshot) bool) Snapshot {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		if n := len(s.list); n > 0 && ok(s.list[n-1]) {
			snap := s.list[n-1]
			s.mu.Unlock()
			return snap
		}
		s.mu.Unlock()
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("no such snapshot; last of %d: %+v", len(s.list), s.list[len(s.list)-1])
	return Snapshot{}
}

func start(t *testing.T, f *fakeService) (*Bridge, *snapshots) {
	snaps := &snapshots{}
	l := newLink(snaps.add)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		l.run(ctx, f.dial)
		close(done)
	}()
	t.Cleanup(func() {
		cancel()
		<-done
	})
	return &Bridge{link: l, version: "1.0.7"}, snaps
}

func TestTheWindowFollowsTheService(t *testing.T) {
	f := newFakeService()
	b, snaps := start(t, f)
	got := snaps.waitFor(t, func(s Snapshot) bool { return s.Service && s.Profiles.SelectedID == "de" })
	if got.Outdated || got.Status.State != ipc.Disconnected || got.Profiles.Profiles[0].Name != "Германия" {
		t.Errorf("snapshot %+v", got)
	}
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	snaps.waitFor(t, func(s Snapshot) bool { return s.Status.State == ipc.Connected })
	if msg, err := b.Import("vless://x"); err != nil || msg != "Добавлено серверов: 1" {
		t.Errorf("import %q %v", msg, err)
	}
	if err := b.Disconnect(); err == nil || err.Error() != "Не выбран сервер" {
		t.Errorf("service error: %v", err)
	}
}

func TestTheWindowReconnectsAfterTheServiceRestarts(t *testing.T) {
	f := newFakeService()
	b, snaps := start(t, f)
	snaps.waitFor(t, func(s Snapshot) bool { return s.Service })
	f.restart()
	snaps.waitFor(t, func(s Snapshot) bool { return !s.Service })
	if err := b.Connect(); err != errNoService {
		t.Errorf("while down: %v", err)
	}
	snaps.waitFor(t, func(s Snapshot) bool { return s.Service })
	if err := b.Connect(); err != nil {
		t.Errorf("after the restart: %v", err)
	}
}

func TestAServiceThatHangsUpAtOnceIsNotHammered(t *testing.T) {
	var mu sync.Mutex
	dials := 0
	hangUp := func(context.Context) (io.ReadWriteCloser, error) {
		mu.Lock()
		dials++
		mu.Unlock()
		a, b := net.Pipe()
		a.Close()
		return b, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		newLink(func(Snapshot) {}).run(ctx, hangUp)
		close(done)
	}()
	time.Sleep(1200 * time.Millisecond)
	cancel()
	<-done
	// At 0, 0.5 and 1.5 seconds: not a busy loop.
	if dials > 3 {
		t.Errorf("%d dials in 1.2 s", dials)
	}
}

func TestAnOutdatedServiceIsNotUsed(t *testing.T) {
	snaps := &snapshots{}
	l := newLink(snaps.add)
	l.onEvent(ipc.NewEvent(ipc.EventHello, ipc.Hello{Version: ipc.Version + 1}))
	if !snaps.list[0].Outdated {
		t.Fatal("not outdated")
	}
	l.setClient(&ipc.Client{})
	if err := l.call(context.Background(), ipc.OpConnect, nil, nil); err != errOutdated {
		t.Errorf("err %v", err)
	}
}

func TestOnlyChangesAreReported(t *testing.T) {
	snaps := &snapshots{}
	l := newLink(snaps.add)
	st := ipc.NewEvent(ipc.EventStatus, ipc.Status{State: ipc.Connecting})
	l.onEvent(st)
	l.onEvent(st)
	if len(snaps.list) != 1 {
		t.Errorf("%d snapshots", len(snaps.list))
	}
}

func TestATooLongImportStaysInTheWindow(t *testing.T) {
	b := &Bridge{link: newLink(func(Snapshot) {})}
	if _, err := b.Import(string(make([]byte, ipc.MaxImport+1))); err == nil {
		t.Error("no error")
	}
}
