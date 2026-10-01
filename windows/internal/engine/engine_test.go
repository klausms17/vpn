package engine

import (
	"context"
	"errors"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// clock is virtual time: timers fire only when the test moves it on.
type clock struct {
	mu     sync.Mutex
	now    time.Time
	timers []*timer
}

type timer struct {
	at      time.Time
	f       func()
	stopped bool
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *clock) AfterFunc(d time.Duration, f func()) func() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	t := &timer{at: c.now.Add(d), f: f}
	c.timers = append(c.timers, t)
	return func() bool {
		c.mu.Lock()
		defer c.mu.Unlock()
		was := !t.stopped
		t.stopped = true
		return was
	}
}

// advance moves time on by d, firing the timers due.
func (c *clock) advance(d time.Duration) {
	c.mu.Lock()
	c.now = c.now.Add(d)
	var due []*timer
	c.timers = slices.DeleteFunc(c.timers, func(t *timer) bool {
		if !t.stopped && !t.at.After(c.now) {
			due = append(due, t)
			return true
		}
		return t.stopped
	})
	c.mu.Unlock()
	for _, t := range due {
		t.f()
	}
}

type fakeCore struct {
	mu       sync.Mutex
	running  bool
	starts   []string
	stops    int
	failures []error // the next starts fail with these
}

func (c *fakeCore) Start(config string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running {
		return errors.New("core is already running")
	}
	c.starts = append(c.starts, config)
	if len(c.failures) > 0 {
		err := c.failures[0]
		c.failures = c.failures[1:]
		return err
	}
	c.running = true
	return nil
}

func (c *fakeCore) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running {
		c.stops++
	}
	c.running = false
	return nil
}

func (c *fakeCore) failNext(errs ...error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.failures = append(c.failures, errs...)
}

func (c *fakeCore) state() (running bool, starts, stops int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running, len(c.starts), c.stops
}

type fakeBinder struct {
	mu    sync.Mutex
	calls []string
}

func (b *fakeBinder) record(s string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.calls = append(b.calls, s)
}

func (b *fakeBinder) Activate()   { b.record("activate") }
func (b *fakeBinder) Settle()     { b.record("settle") }
func (b *fakeBinder) Deactivate() { b.record("deactivate") }

func (b *fakeBinder) last() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if len(b.calls) == 0 {
		return ""
	}
	return b.calls[len(b.calls)-1]
}

type fakeRuntime struct {
	mu        sync.Mutex
	shouldRun bool
}

func (r *fakeRuntime) ShouldRun() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.shouldRun
}

func (r *fakeRuntime) SetShouldRun(v bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.shouldRun = v
}

type world struct {
	t        *testing.T
	e        *Engine
	core     *fakeCore
	binder   *fakeBinder
	runtime  *fakeRuntime
	clock    *clock
	mu       sync.Mutex
	statuses []ipc.Status
	profiles model.ProfilesState
	logs     []string
}

var germany = model.StoredProfile{ID: "de", Name: "Германия", Protocol: "vless", Network: "tcp", Security: "reality"}

func newWorld(t *testing.T) *world {
	w := &world{
		t:        t,
		core:     &fakeCore{},
		binder:   &fakeBinder{},
		runtime:  &fakeRuntime{},
		clock:    &clock{now: time.Unix(1_700_000_000, 0)},
		profiles: model.ProfilesState{Profiles: []model.StoredProfile{germany}, SelectedID: "de"},
	}
	w.e = New(Deps{
		Core:    w.core,
		Binder:  w.binder,
		Runtime: w.runtime,
		Clock:   w.clock,
		Profiles: func() (model.ProfilesState, error) {
			w.mu.Lock()
			defer w.mu.Unlock()
			return w.profiles, nil
		},
		Build: func(p model.StoredProfile) (string, error) {
			if p.ID == "bug" {
				panic("a bug")
			}
			return "config of " + p.ID, nil
		},
		TrimLog: func() {},
		Publish: func(s ipc.Status) {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.statuses = append(w.statuses, s)
		},
		Log: func(m string) {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.logs = append(w.logs, m)
		},
	})
	ctx, cancel := context.WithCancel(context.Background())
	stopped := make(chan struct{})
	go func() {
		w.e.Run(ctx)
		close(stopped)
	}()
	t.Cleanup(func() {
		cancel()
		<-stopped
	})
	return w
}

// sync waits until every job posted so far has run.
func (w *world) sync() {
	done := make(chan struct{})
	w.e.post(func() { close(done) })
	<-done
}

// advance lets the jobs posted so far run, so their timers are set, then
// moves time on and lets the jobs of the timers that fired run.
func (w *world) advance(d time.Duration) {
	w.sync()
	w.clock.advance(d)
	w.sync()
}

func (w *world) states() []int {
	w.mu.Lock()
	defer w.mu.Unlock()
	var s []int
	for _, st := range w.statuses {
		s = append(s, st.State)
	}
	return s
}

func (w *world) last() ipc.Status {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.statuses[len(w.statuses)-1]
}

func (w *world) clear() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.statuses = nil
}

func (w *world) expectStates(want ...int) {
	w.t.Helper()
	if got := w.states(); !slices.Equal(got, want) {
		w.t.Errorf("states %v, want %v", got, want)
	}
}

func TestConnectBringsTheSelectedServerUp(t *testing.T) {
	w := newWorld(t)
	w.e.Connect()
	w.sync()
	w.expectStates(ipc.Connecting, ipc.Connecting, ipc.Connected)
	got := w.last()
	if got.ProfileID != "de" || got.ProfileName != "Германия" || got.ConnectedSince != w.clock.Now().UnixMilli() || got.Message != "" {
		t.Errorf("status %+v", got)
	}
	if running, starts, _ := w.core.state(); !running || starts != 1 || w.core.starts[0] != "config of de" {
		t.Errorf("core running %v, starts %v", running, w.core.starts)
	}
	if !slices.Equal(w.binder.calls, []string{"deactivate", "activate", "settle"}) {
		t.Errorf("binder %v", w.binder.calls)
	}
	if !w.runtime.ShouldRun() {
		t.Error("should_run not set")
	}
	// Connecting again leaves the tunnel as it is.
	w.clear()
	w.e.Connect()
	w.sync()
	if _, starts, stops := w.core.state(); starts != 1 || stops != 0 || len(w.states()) != 0 {
		t.Errorf("restarted: %d starts, %d stops, %v", starts, stops, w.states())
	}
}

func TestNoServerIsAnErrorForTheUser(t *testing.T) {
	w := newWorld(t)
	w.profiles = model.ProfilesState{}
	w.e.Connect()
	w.sync()
	w.expectStates(ipc.Connecting, ipc.Failed)
	if m := w.last().Message; m != "Не выбран сервер. Добавьте ключ." {
		t.Errorf("message %q", m)
	}
	if _, starts, _ := w.core.state(); starts != 0 {
		t.Error("core started")
	}
	if w.runtime.ShouldRun() {
		t.Error("should_run set")
	}
}

func TestAConnectTheUserAskedForGivesUpAtOnce(t *testing.T) {
	w := newWorld(t)
	w.core.failNext(errors.New("unable to create the adapter"))
	w.e.Connect()
	w.sync()
	w.expectStates(ipc.Connecting, ipc.Connecting, ipc.Failed)
	if m := w.last().Message; m != "Ядро не запустилось: unable to create the adapter" {
		t.Errorf("message %q", m)
	}
	if w.binder.last() != "deactivate" || w.runtime.ShouldRun() {
		t.Errorf("binder %v, should_run %v", w.binder.calls, w.runtime.ShouldRun())
	}
	// Nothing is retried.
	w.advance(time.Minute)
	if _, starts, _ := w.core.state(); starts != 1 {
		t.Errorf("%d starts", starts)
	}
}

func TestATunnelThatShouldRunIsTriedThreeTimesAtBoot(t *testing.T) {
	w := newWorld(t)
	w.runtime.SetShouldRun(true)
	boom := errors.New("routes cannot be set")
	w.core.failNext(boom, boom, boom)
	w.e.Resume()
	w.sync()
	if s := w.last(); s.State != ipc.Connecting || s.Message != "Переподключение…" {
		t.Errorf("after the first failure: %+v", s)
	}
	w.advance(1499 * time.Millisecond)
	if _, starts, _ := w.core.state(); starts != 1 {
		t.Fatalf("retried early: %d starts", starts)
	}
	w.advance(time.Millisecond)
	if _, starts, _ := w.core.state(); starts != 2 {
		t.Fatalf("%d starts after 1.5 s", starts)
	}
	w.advance(5 * time.Second)
	if _, starts, _ := w.core.state(); starts != 3 {
		t.Fatalf("%d starts after 5 s more", starts)
	}
	if s := w.last(); s.State != ipc.Failed || !strings.Contains(s.Message, "routes cannot be set") {
		t.Errorf("after the third failure: %+v", s)
	}
	if w.runtime.ShouldRun() {
		t.Error("should_run kept after giving up")
	}
	w.advance(time.Minute)
	if _, starts, _ := w.core.state(); starts != 3 {
		t.Errorf("%d starts", starts)
	}
}

func TestARetryThatWorksBringsTheTunnelUp(t *testing.T) {
	w := newWorld(t)
	w.runtime.SetShouldRun(true)
	w.core.failNext(errors.New("not yet"))
	w.e.Resume()
	w.advance(1500 * time.Millisecond)
	if s := w.last(); s.State != ipc.Connected || s.ProfileID != "de" {
		t.Errorf("status %+v", s)
	}
}

func TestNothingResumesWhenTheTunnelShouldNotRun(t *testing.T) {
	w := newWorld(t)
	w.e.Resume()
	w.sync()
	if len(w.states()) != 0 {
		t.Errorf("states %v", w.states())
	}
}

func TestDisconnectStopsEverythingAndDropsARetry(t *testing.T) {
	w := newWorld(t)
	w.runtime.SetShouldRun(true)
	w.core.failNext(errors.New("not yet"))
	w.e.Resume()
	w.sync()
	w.clear()
	w.e.Disconnect()
	w.sync()
	w.expectStates(ipc.Disconnecting, ipc.Disconnected)
	if w.runtime.ShouldRun() || w.binder.last() != "deactivate" {
		t.Errorf("should_run %v, binder %v", w.runtime.ShouldRun(), w.binder.calls)
	}
	w.advance(time.Minute)
	if _, starts, _ := w.core.state(); starts != 1 {
		t.Errorf("the retry ran: %d starts", starts)
	}
}

func TestDisconnectWhileUp(t *testing.T) {
	w := newWorld(t)
	w.e.Connect()
	w.sync()
	w.clear()
	w.e.Disconnect()
	w.sync()
	w.expectStates(ipc.Disconnecting, ipc.Disconnected)
	if running, _, stops := w.core.state(); running || stops != 1 {
		t.Errorf("running %v, stops %d", running, stops)
	}
	if s := w.last(); s.Message != "" {
		t.Errorf("message %q", s.Message)
	}
}

func TestADisconnectDuringAStartWins(t *testing.T) {
	w := newWorld(t)
	// The start is queued, the disconnect follows before it ran.
	block := make(chan struct{})
	w.e.post(func() { <-block })
	w.e.Connect()
	w.e.Disconnect()
	close(block)
	w.sync()
	if s := w.last(); s.State != ipc.Disconnected {
		t.Errorf("status %+v", s)
	}
	if running, _, _ := w.core.state(); running || w.runtime.ShouldRun() {
		t.Errorf("running %v, should_run %v", running, w.runtime.ShouldRun())
	}
}

func TestANetworkSwitchResetsAnEstablishedTunnel(t *testing.T) {
	w := newWorld(t)
	w.e.Connect()
	w.sync()
	// Too soon after the start: nothing to reset yet.
	w.e.NetworkSwitched()
	w.advance(2 * time.Second)
	if _, starts, _ := w.core.state(); starts != 1 {
		t.Fatalf("%d starts", starts)
	}
	w.advance(2 * time.Second)
	w.clear()
	// Two switches close together reset once, 1.5 s after the last one.
	w.e.NetworkSwitched()
	w.advance(time.Second)
	w.e.NetworkSwitched()
	w.advance(1499 * time.Millisecond)
	if _, starts, _ := w.core.state(); starts != 1 {
		t.Fatalf("reset early: %d starts", starts)
	}
	w.advance(time.Millisecond)
	if running, starts, stops := w.core.state(); !running || starts != 2 || stops != 1 || w.core.starts[1] != "config of de" {
		t.Errorf("running %v, %d starts, %d stops", running, starts, stops)
	}
	// The window still sees the same connected tunnel.
	if len(w.states()) != 0 {
		t.Errorf("states %v", w.states())
	}
	if w.binder.last() != "settle" {
		t.Errorf("binder %v", w.binder.calls)
	}
}

func TestAResetThatFailsStartsAgain(t *testing.T) {
	w := newWorld(t)
	w.e.Connect()
	w.sync()
	w.advance(10 * time.Second)
	w.core.failNext(errors.New("adapter busy"), errors.New("adapter busy"))
	w.e.Resumed()
	w.advance(5 * time.Second)
	// The reset and the start right after it failed; a retry waits.
	if s := w.last(); s.State != ipc.Connecting || s.Message != "Переподключение…" {
		t.Fatalf("status %+v", s)
	}
	w.advance(1500 * time.Millisecond)
	if s := w.last(); s.State != ipc.Connected {
		t.Errorf("status %+v", s)
	}
	if _, starts, _ := w.core.state(); starts != 4 {
		t.Errorf("%d starts", starts)
	}
}

func TestADisconnectCancelsAPendingReset(t *testing.T) {
	w := newWorld(t)
	w.e.Connect()
	w.sync()
	w.e.Resumed()
	w.e.Disconnect()
	w.advance(time.Minute)
	if running, starts, _ := w.core.state(); running || starts != 1 {
		t.Errorf("running %v, %d starts", running, starts)
	}
}

func TestStoppingTheServiceKeepsShouldRun(t *testing.T) {
	w := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	e := New(w.e.d)
	stopped := make(chan struct{})
	go func() {
		e.Run(ctx)
		close(stopped)
	}()
	e.Connect()
	done := make(chan struct{})
	e.post(func() { close(done) })
	<-done
	cancel()
	<-stopped
	if running, _, _ := w.core.state(); running {
		t.Error("core left running")
	}
	if !w.runtime.ShouldRun() {
		t.Error("should_run cleared: the tunnel would not come back at boot")
	}
	if e.Status().State != ipc.Disconnected {
		t.Errorf("status %+v", e.Status())
	}
	// Posting after the end never blocks.
	for range 100 {
		e.Connect()
	}
}

func TestTheLogNeverNamesTheServer(t *testing.T) {
	w := newWorld(t)
	w.mu.Lock()
	w.profiles.Profiles[0].Address = "vpn.example.com"
	w.mu.Unlock()
	w.core.failNext(errors.New(`invalid address: "vpn.example.com"`))
	w.e.Connect()
	w.sync()
	if got := w.last(); got.State != ipc.Failed || !strings.Contains(got.Message, "vpn.example.com") {
		t.Errorf("the user sees %+v", got)
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, l := range w.logs {
		if strings.Contains(l, "vpn.example.com") {
			t.Errorf("logged %q", l)
		}
	}
	if !slices.ContainsFunc(w.logs, func(l string) bool { return strings.Contains(l, `invalid address: "<server>"`) }) {
		t.Errorf("logs %q", w.logs)
	}
}

func TestABugTakesTheTunnelDownNotTheService(t *testing.T) {
	w := newWorld(t)
	w.e.Connect()
	w.sync()
	w.mu.Lock()
	w.profiles.Profiles = append(w.profiles.Profiles, model.StoredProfile{ID: "bug", Name: "Ошибка"})
	w.profiles.SelectedID = "bug"
	w.mu.Unlock()
	w.e.Disconnect()
	w.e.Connect()
	w.sync()
	if got := w.last(); got.State != ipc.Failed || got.Message != "Внутренняя ошибка Kirov VPN. Подключитесь снова." {
		t.Errorf("status %+v", got)
	}
	if running, _, _ := w.core.state(); running || w.runtime.ShouldRun() {
		t.Errorf("after the bug: core running %v, should run %v", running, w.runtime.ShouldRun())
	}
	w.mu.Lock()
	logged := slices.ContainsFunc(w.logs, func(l string) bool { return strings.HasPrefix(l, "engine bug: a bug\n") })
	w.profiles.SelectedID = "de"
	w.mu.Unlock()
	if !logged {
		t.Error("the bug was not logged")
	}
	// The engine still works.
	w.e.Connect()
	w.sync()
	if got := w.last(); got.State != ipc.Connected {
		t.Errorf("then: %+v", got)
	}
}
