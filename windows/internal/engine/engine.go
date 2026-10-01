// Package engine starts, stops and resets the tunnel, as the Android app's
// TunnelEngine does. One goroutine runs every start, stop and reset, one
// after another, and owns the session that says which tunnel runs. Xray
// creates the Windows adapter at start and removes it at stop, so unlike
// Android a restart cannot bring the new tunnel up before the old one goes.
package engine

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/client/tunnel"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// Core is the Xray core: libxray's Controller.
type Core interface {
	Start(config string) error
	// Stop is safe when nothing runs.
	Stop() error
}

// Binder keeps the service's own sockets outside the tunnel (netbind).
type Binder interface {
	Activate()
	Settle()
	Deactivate()
}

// Runtime is what outlives the service process.
type Runtime interface {
	ShouldRun() bool
	SetShouldRun(bool)
}

// Clock is time; a fake in tests.
type Clock interface {
	Now() time.Time
	// AfterFunc runs f after d unless stop is called first.
	AfterFunc(d time.Duration, f func()) (stop func() bool)
}

// Deps is what the engine works with.
type Deps struct {
	Core    Core
	Binder  Binder
	Runtime Runtime
	Clock   Clock
	// Profiles reads the saved servers.
	Profiles func() (model.ProfilesState, error)
	// Build makes the core's config for a server.
	Build func(model.StoredProfile) (string, error)
	// TrimLog runs before each start of the core, to keep its log small.
	TrimLog func()
	// Publish gets every status in order. It must not block or call back.
	Publish func(ipc.Status)
	Log     func(string)
}

// When to reset the core after the network changed or the PC woke up
// (Android's HealthMonitor; the plan's section 2.5).
const (
	networkSettle     = 1500 * time.Millisecond
	minUptimeForReset = 3 * time.Second
	resumeSettle      = 5 * time.Second
)

// Engine owns the tunnel. Its methods may be called from any goroutine;
// the work runs in Run.
type Engine struct {
	d    Deps
	jobs chan func()
	done chan struct{}

	statusMu sync.Mutex
	status   atomic.Pointer[ipc.Status]

	// Only in Run.
	session *session
	// held: a failed start waits for its retry.
	held bool
	// generation counts starts and stops; a retry or reset planned before
	// a newer one is dropped.
	generation int
	stopReset  func() bool
}

type session struct {
	profile model.StoredProfile
	config  string
	up      time.Time
}

// userError is a start failure whose text is meant for the user as it is.
type userError struct{ msg string }

func (e *userError) Error() string { return e.msg }

// New returns an engine that is idle until Run.
func New(d Deps) *Engine {
	e := &Engine{d: d, jobs: make(chan func(), 64), done: make(chan struct{})}
	e.status.Store(&ipc.Status{State: ipc.Disconnected})
	return e
}

// Run does the engine's work until ctx ends. Then it stops the core but
// keeps should_run, so the tunnel comes back when the service starts again.
func (e *Engine) Run(ctx context.Context) {
	defer close(e.done)
	for {
		select {
		case job := <-e.jobs:
			job()
		case <-ctx.Done():
			if e.session != nil || e.held {
				e.halt()
				e.held = false
				e.publish(ipc.Status{State: ipc.Disconnected})
				e.d.Log("tunnel down: the service is stopping")
			}
			return
		}
	}
}

// Status returns the current status.
func (e *Engine) Status() ipc.Status { return *e.status.Load() }

// Connect is the user's connect. A tunnel that runs stays as it is.
func (e *Engine) Connect() {
	e.post(func() {
		if e.session == nil {
			e.start(true, 0)
		}
	})
}

// Resume brings back a tunnel that should run, when the service starts.
func (e *Engine) Resume() {
	e.post(func() {
		if e.session == nil && !e.held && e.d.Runtime.ShouldRun() {
			e.d.Log("resuming the tunnel")
			e.start(false, 0)
		}
	})
}

// Disconnect is the user's disconnect. It shows at once, even while a
// start is still running.
func (e *Engine) Disconnect() {
	e.d.Runtime.SetShouldRun(false)
	e.publish(ipc.Status{State: ipc.Disconnecting, ProfileName: e.Status().ProfileName})
	e.post(func() { e.stop() })
}

// NetworkSwitched tells that traffic now leaves through another network.
// Connections opened over the old one are dead but would hang until their
// timeouts; restarting the core ends them at once, so apps reconnect.
func (e *Engine) NetworkSwitched() {
	e.post(func() {
		if e.session != nil && e.d.Clock.Now().Sub(e.session.up) >= minUptimeForReset {
			e.resetLater("network changed, resetting connections", networkSettle)
		}
	})
}

// Resumed tells that the PC woke from sleep, after which most connections
// are dead.
func (e *Engine) Resumed() {
	e.post(func() {
		if e.session != nil {
			e.resetLater("woke from sleep, resetting connections", resumeSettle)
		}
	})
}

func (e *Engine) post(job func()) {
	select {
	case e.jobs <- job:
	case <-e.done:
	}
}

func (e *Engine) publish(s ipc.Status) {
	e.statusMu.Lock()
	defer e.statusMu.Unlock()
	e.status.Store(&s)
	e.d.Publish(s)
}

func (e *Engine) start(userRequested bool, attempt int) {
	restarting := e.session != nil || e.held
	e.generation++
	generation := e.generation
	before := e.Status()
	if restarting && before.State == ipc.Connected {
		// The old tunnel works until the new one replaces it.
		next := before
		next.Message = "Применяем изменения…"
		e.publish(next)
	} else {
		e.publish(ipc.Status{State: ipc.Connecting, ProfileName: before.ProfileName})
	}
	swapped := false
	err := func() error {
		saved, err := e.d.Profiles()
		if err != nil {
			return err
		}
		profile, ok := saved.Selected()
		if !ok {
			return &userError{"Не выбран сервер. Добавьте ключ."}
		}
		if e.Status().State == ipc.Connecting {
			e.publish(ipc.Status{State: ipc.Connecting, ProfileID: profile.ID, ProfileName: profile.Name})
		}
		config, err := e.d.Build(profile)
		if err != nil {
			return err
		}
		// From here on the old tunnel is gone.
		swapped = true
		e.halt()
		e.d.TrimLog()
		e.d.Binder.Activate()
		if err := e.d.Core.Start(config); err != nil {
			return &userError{"Ядро не запустилось: " + err.Error()}
		}
		e.d.Binder.Settle()
		now := e.d.Clock.Now()
		e.session = &session{profile: profile, config: config, up: now}
		e.held = false
		e.d.Runtime.SetShouldRun(true)
		e.publish(ipc.Status{State: ipc.Connected, ProfileID: profile.ID, ProfileName: profile.Name, ConnectedSince: now.UnixMilli()})
		e.d.Log(fmt.Sprintf("tunnel up: %s/%s/%s", profile.Protocol, profile.Network, profile.Security))
		return nil
	}()
	if err != nil {
		e.startFailed(err, userRequested, attempt, generation, restarting, swapped, before)
	}
}

func (e *Engine) startFailed(err error, userRequested bool, attempt, generation int, restarting, swapped bool, before ipc.Status) {
	message := err.Error()
	var ue *userError
	if !errors.As(err, &ue) {
		message = "Ошибка запуска: " + message
	}
	e.d.Log(fmt.Sprintf("tunnel start failed (attempt %d): %s", attempt+1, message))
	action := tunnel.Decide(tunnel.StartFailure{
		Restarting:    restarting,
		Swapped:       swapped,
		SessionAlive:  e.session != nil,
		UserRequested: userRequested,
		Attempt:       attempt,
	}, e.d.Runtime.ShouldRun)
	switch action {
	case tunnel.KeepOld, tunnel.KeepOldAndRetry:
		old := e.session.profile
		since := before.ConnectedSince
		if since == 0 {
			since = e.d.Clock.Now().UnixMilli()
		}
		e.publish(ipc.Status{State: ipc.Connected, ProfileID: old.ID, ProfileName: old.Name, Message: "Не удалось применить изменения: " + message, ConnectedSince: since})
		if action == tunnel.KeepOldAndRetry {
			e.retryLater(generation, attempt+1)
		}
	case tunnel.HoldAndRetry:
		e.halt()
		e.held = true
		e.publish(ipc.Status{State: ipc.Connecting, ProfileName: before.ProfileName, Message: "Переподключение…"})
		e.retryLater(generation, attempt+1)
	default:
		e.halt()
		e.held = false
		e.d.Runtime.SetShouldRun(false)
		e.publish(ipc.Status{State: ipc.Failed, Message: message})
	}
}

// retryLater tries the start once more after a pause, as a start nobody
// asked for just now, unless the tunnel was started or stopped meanwhile.
func (e *Engine) retryLater(generation, attempt int) {
	e.d.Clock.AfterFunc(tunnel.RetryDelay(attempt), func() {
		e.post(func() {
			if generation != e.generation || !e.d.Runtime.ShouldRun() {
				return
			}
			e.d.Log(fmt.Sprintf("starting again (attempt %d)", attempt+1))
			e.start(false, attempt)
		})
	})
}

func (e *Engine) stop() {
	e.generation++
	// A start that was still running has just finished: say again that
	// the tunnel is going down.
	if s := e.Status(); s.State != ipc.Disconnecting {
		e.publish(ipc.Status{State: ipc.Disconnecting, ProfileName: s.ProfileName})
	}
	e.halt()
	e.held = false
	// A start that finished just before this set it again.
	e.d.Runtime.SetShouldRun(false)
	e.publish(ipc.Status{State: ipc.Disconnected})
	e.d.Log("tunnel down")
}

// resetLater restarts the running core with the same server and settings
// after delay; a reset already waiting is replaced.
func (e *Engine) resetLater(why string, delay time.Duration) {
	e.cancelReset()
	generation := e.generation
	e.stopReset = e.d.Clock.AfterFunc(delay, func() {
		e.post(func() {
			if generation == e.generation && e.session != nil {
				e.resetNow(why)
			}
		})
	})
}

func (e *Engine) resetNow(why string) {
	e.stopReset = nil
	running := e.session
	e.d.Log(why)
	if err := e.d.Core.Stop(); err != nil {
		e.d.Log("core stop: " + err.Error())
	}
	e.d.TrimLog()
	e.d.Binder.Activate()
	if err := e.d.Core.Start(running.config); err != nil {
		e.d.Log("core restart failed: " + err.Error())
		e.session = nil
		e.d.Binder.Deactivate()
		if !e.d.Runtime.ShouldRun() {
			e.publish(ipc.Status{State: ipc.Disconnected})
			return
		}
		// It was running: a restart, tried again on failure.
		e.held = true
		e.start(false, 0)
		return
	}
	e.d.Binder.Settle()
	e.session = &session{profile: running.profile, config: running.config, up: e.d.Clock.Now()}
}

// halt stops the core; nothing runs afterwards.
func (e *Engine) halt() {
	e.cancelReset()
	e.session = nil
	if err := e.d.Core.Stop(); err != nil {
		e.d.Log("core stop: " + err.Error())
	}
	e.d.Binder.Deactivate()
}

func (e *Engine) cancelReset() {
	if e.stopReset != nil {
		e.stopReset()
		e.stopReset = nil
	}
}
