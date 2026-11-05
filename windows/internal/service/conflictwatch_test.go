package service

import (
	"context"
	"errors"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeRoutes is Windows' routing table, which tells its subscriber of each
// change.
type fakeRoutes struct {
	mu           sync.Mutex
	table        routeTable
	changed      func()
	unsubscribed int
}

func (f *fakeRoutes) read() routeTable {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.table
}

func (f *fakeRoutes) set(t routeTable) {
	f.mu.Lock()
	f.table = t
	changed := f.changed
	f.mu.Unlock()
	if changed != nil {
		changed()
	}
}

func (f *fakeRoutes) subscribe(changed func()) (func(), error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.changed = changed
	return func() {
		f.mu.Lock()
		defer f.mu.Unlock()
		f.changed = nil
		f.unsubscribed++
	}, nil
}

func (f *fakeRoutes) subscribed() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.changed != nil
}

func (f *fakeRoutes) unsubscriptions() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.unsubscribed
}

func watchFor(t *testing.T, d conflictDeps) (w *conflictWatch, reports chan string) {
	reports = make(chan string, 16)
	d.report = func(notice string) { reports <- notice }
	if d.log == nil {
		d.log = func(string) {}
	}
	w = newConflictWatch(d)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		w.run(ctx)
		close(done)
	}()
	t.Cleanup(func() {
		cancel()
		<-done
	})
	return w, reports
}

func next(t *testing.T, reports chan string) string {
	t.Helper()
	select {
	case r := <-reports:
		return r
	case <-time.After(5 * time.Second):
		t.Fatal("nothing reported")
		return ""
	}
}

func quiet(t *testing.T, reports chan string) {
	t.Helper()
	select {
	case r := <-reports:
		t.Fatalf("reported %q", r)
	case <-time.After(100 * time.Millisecond):
	}
}

func TestConflictWatch(t *testing.T) {
	ours := tunnelRoutes(t)
	clean := routeTable{tunnel: tunLUID, routes: ours, adapters: testAdapters}
	taken := clean
	taken.routes = append(append([]route{}, ours...), on(browsecLUID, 0, "88.0.0.0/6")...)
	routes := &fakeRoutes{table: taken}
	var logged []string
	var logMu sync.Mutex
	w, reports := watchFor(t, conflictDeps{read: routes.read, subscribe: routes.subscribe, settle: 10 * time.Millisecond, every: time.Hour,
		log: func(s string) {
			logMu.Lock()
			defer logMu.Unlock()
			logged = append(logged, s)
		}})

	quiet(t, reports)
	w.tunnel(true)
	if r := next(t, reports); !strings.Contains(r, "Browsec") {
		t.Fatalf("at once: %q", r)
	}
	routes.set(clean)
	if r := next(t, reports); r != "" {
		t.Fatalf("once Browsec is off: %q", r)
	}
	routes.set(taken)
	if r := next(t, reports); !strings.Contains(r, "Browsec") {
		t.Fatalf("Browsec on again: %q", r)
	}
	// The tunnel restarts: its routes are gone for a moment.
	routes.set(routeTable{})
	quiet(t, reports)
	routes.set(taken)
	quiet(t, reports)
	w.tunnel(false)
	if r := next(t, reports); r != "" {
		t.Fatalf("with the tunnel down: %q", r)
	}
	if n := routes.unsubscriptions(); n != 1 {
		t.Errorf("unsubscribed %d times", n)
	}
	routes.set(clean)
	quiet(t, reports)
	logMu.Lock()
	defer logMu.Unlock()
	if len(logged) != 3 || !strings.Contains(logged[0], "Browsec") || !strings.Contains(logged[1], "no other VPN") {
		t.Errorf("logged %q", logged)
	}
}

func TestConflictWatchReadsOnceChangesPause(t *testing.T) {
	// WireGuard takes 8.8.8.0/24, which the tunnel's 8.0.0.0/7 carries.
	other := on(wireguardLUID, 0, "8.8.8.0/24")
	table := func(ours []route) routeTable {
		return routeTable{tunnel: tunLUID, routes: append(append([]route{}, ours...), other...), adapters: testAdapters}
	}
	var cover route
	var rest []route
	for _, r := range tunnelRoutes(t) {
		if r.prefix == netip.MustParsePrefix("8.0.0.0/7") {
			cover = r
		} else {
			rest = append(rest, r)
		}
	}
	routes := &fakeRoutes{table: table(append(rest, cover))}
	const settle = 200 * time.Millisecond
	w, reports := watchFor(t, conflictDeps{read: routes.read, subscribe: routes.subscribe, settle: settle, every: time.Hour})
	w.tunnel(true)
	if r := next(t, reports); !strings.Contains(r, "WireGuard") {
		t.Fatalf("at once: %q", r)
	}
	// The tunnel restarts and Xray sets its routes again over longer than
	// settle, 8.0.0.0/7 last: nothing is read until it is done.
	routes.set(table(nil))
	const steps = 10
	for i := 1; i <= steps; i++ {
		time.Sleep(settle / 8)
		routes.set(table(rest[:len(rest)*i/steps]))
	}
	time.Sleep(settle / 8)
	routes.set(table(append(rest, cover)))
	select {
	case r := <-reports:
		t.Fatalf("reported %q amid the changes", r)
	case <-time.After(3 * settle):
	}
	other = nil
	routes.set(table(append(rest, cover)))
	if r := next(t, reports); r != "" {
		t.Fatalf("once WireGuard is off: %q", r)
	}
}

func TestConflictWatchWithoutNotifications(t *testing.T) {
	routes := &fakeRoutes{}
	var logged []string
	var logMu sync.Mutex
	w, reports := watchFor(t, conflictDeps{read: routes.read, settle: time.Hour, every: 10 * time.Millisecond,
		subscribe: func(func()) (func(), error) { return nil, errors.New("no notifications") },
		log: func(s string) {
			logMu.Lock()
			defer logMu.Unlock()
			logged = append(logged, s)
		}})
	w.tunnel(true)
	quiet(t, reports)
	taken := routeTable{tunnel: tunLUID, routes: append(tunnelRoutes(t), on(wireguardLUID, 0, "8.8.8.0/24")...), adapters: testAdapters}
	routes.set(taken)
	if r := next(t, reports); !strings.Contains(r, "WireGuard") {
		t.Fatalf("checked now and then: %q", r)
	}
	logMu.Lock()
	defer logMu.Unlock()
	if len(logged) == 0 || !strings.Contains(logged[0], "cannot be watched") {
		t.Errorf("logged %q", logged)
	}
}

func TestConflictWatchStopsWithTheTunnel(t *testing.T) {
	routes := &fakeRoutes{}
	w, reports := watchFor(t, conflictDeps{read: routes.read, subscribe: routes.subscribe, settle: 10 * time.Millisecond, every: 10 * time.Millisecond})
	w.tunnel(true)
	for i := 0; i < 100 && !routes.subscribed(); i++ {
		time.Sleep(10 * time.Millisecond)
	}
	w.tunnel(false)
	for i := 0; i < 100 && routes.subscribed(); i++ {
		time.Sleep(10 * time.Millisecond)
	}
	if routes.subscribed() || routes.unsubscriptions() != 1 {
		t.Fatal("still subscribed")
	}
	routes.set(routeTable{tunnel: tunLUID, routes: append(tunnelRoutes(t), on(browsecLUID, 0, "88.0.0.0/6")...), adapters: testAdapters})
	quiet(t, reports)
}
