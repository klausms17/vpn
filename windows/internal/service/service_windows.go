package service

import (
	"context"
	"fmt"
	stdlog "log"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/applog"
	"github.com/klausms17/vpn/libxray/client/importer"
	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/client/store"
	"github.com/klausms17/vpn/libxray/client/tunnel"
	"github.com/klausms17/vpn/windows/internal/engine"
	"github.com/klausms17/vpn/windows/internal/ipc"
	"github.com/klausms17/vpn/windows/internal/netbind"
	"github.com/klausms17/vpn/windows/internal/winsys"
	"golang.org/x/sys/windows/svc"
)

const (
	// Name is the service's name; Display is what the services list shows.
	Name    = "KirovVPN"
	Display = "Kirov VPN"
	// adapter names the tunnel's network adapter (libxray's windowsAdapter).
	adapter = "Kirov VPN"
	// stopWait bounds how long stopping the tunnel may take.
	stopWait = 15 * time.Second
)

// pbtAPMResumeAutomatic is the power event of a wake from sleep.
const pbtAPMResumeAutomatic = 0x12

// Run runs the service until the Service Control Manager stops it.
func Run(version string) error {
	return svc.Run(Name, &winService{version: version})
}

type winService struct{ version string }

func (w *winService) Execute(_ []string, requests <-chan svc.ChangeRequest, changes chan<- svc.Status) (bool, uint32) {
	changes <- svc.Status{State: svc.StartPending}
	a, err := start(w.version)
	if err != nil {
		if a != nil {
			a.log.Error("service did not start: " + err.Error())
		}
		// A failure the recovery actions answer by starting it again.
		return true, 1
	}
	changes <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown | svc.AcceptPowerEvent}
	for req := range requests {
		switch req.Cmd {
		case svc.Interrogate:
			changes <- req.CurrentStatus
		case svc.Stop, svc.Shutdown:
			changes <- svc.Status{State: svc.StopPending, WaitHint: uint32((stopWait + 5*time.Second).Milliseconds())}
			a.stop()
			return false, 0
		case svc.PowerEvent:
			if req.EventType == pbtAPMResumeAutomatic {
				a.log.Info("woke from sleep")
				a.engine.Resumed()
			}
		}
	}
	return false, 0
}

type app struct {
	log      *applog.Log
	engine   *engine.Engine
	listener net.Listener
	cancel   context.CancelFunc
	stopped  chan struct{}
}

// start sets everything up and resumes a tunnel that should run. The app
// it returns on failure has only the log.
func start(version string) (*app, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	// Next to the programs, where only administrators can create anything
	// (ProgramData lets every user make folders), as WireGuard does.
	root := filepath.Join(filepath.Dir(exe), "Data")
	if err := winsys.SecureDir(root); err != nil {
		return nil, fmt.Errorf("data folder: %w", err)
	}
	dataDir, logDir := filepath.Join(root, "data"), filepath.Join(root, "logs")
	for _, dir := range []string{dataDir, logDir} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, err
		}
	}
	log := applog.New(filepath.Join(logDir, "service.log"))
	a := &app{log: log, stopped: make(chan struct{})}
	// wintun logs through Go's log package.
	stdlog.SetFlags(0)
	stdlog.SetOutput(log)
	log.Info(fmt.Sprintf("service %s starting, Xray %s", version, libxray.Version()))
	if err := libxray.SetCrashLog(filepath.Join(logDir, "go-crash.log")); err != nil {
		log.Warn("no crash log: " + err.Error())
	}
	libxray.InitEnv(filepath.Join(filepath.Dir(exe), "geo"))

	profiles := store.New(filepath.Join(dataDir, "profiles.json"),
		func() model.ProfilesState { return model.ProfilesState{} },
		winsys.DPAPI{Name: "Kirov VPN profiles"}, log.Warn)
	runtime := &runtimeStore{
		s:   store.New(filepath.Join(dataDir, "runtime.json"), func() runtimeState { return runtimeState{} }, nil, log.Warn),
		log: log.Error,
	}
	var (
		eng    *engine.Engine
		server *ipc.Server
	)
	binder, err := netbind.New(adapter, log.Info, func(prev, next netbind.Choice) {
		// Only a move from one network to another; losing the network or
		// getting it back leaves the connections that may still work.
		if prev != (netbind.Choice{}) && next != (netbind.Choice{}) {
			eng.NetworkSwitched()
		}
	})
	if err != nil {
		return a, fmt.Errorf("network watch: %w", err)
	}
	xrayLog := filepath.Join(logDir, "xray.log")
	eng = engine.New(engine.Deps{
		Core:     controller{libxray.NewController()},
		Binder:   binder,
		Runtime:  runtime,
		Clock:    realClock{},
		Profiles: profiles.ReadStrict,
		Build:    buildConfig(xrayLog),
		TrimLog:  func() { tunnel.TrimLog(xrayLog, tunnel.LogMaxBytes, tunnel.LogKeepBytes) },
		Publish:  func(s ipc.Status) { server.Broadcast(ipc.NewEvent(ipc.EventStatus, s)) },
		Log:      log.Info,
	})
	h := &handler{
		tunnel:   eng,
		profiles: profiles,
		keys: func(ctx context.Context, text string) ([]model.Key, []string, error) {
			return importer.Keys(ctx, text, importer.ForService)
		},
		broadcast: func(ev ipc.Event) { server.Broadcast(ev) },
		log:       log.Info,
		now:       time.Now,
	}
	server = ipc.NewServer(h.handle, h.greet, log.Warn)
	a.engine = eng
	a.listener, err = ipc.Listen()
	if err != nil {
		return a, fmt.Errorf("pipe: %w", err)
	}
	var ctx context.Context
	ctx, a.cancel = context.WithCancel(context.Background())
	go func() {
		eng.Run(ctx)
		close(a.stopped)
	}()
	go func() {
		if err := server.Serve(a.listener); err != nil {
			log.Error("pipe: " + err.Error())
		}
	}()
	eng.Resume()
	return a, nil
}

func (a *app) stop() {
	a.log.Info("service stopping")
	_ = a.listener.Close()
	a.cancel()
	select {
	case <-a.stopped:
	case <-time.After(stopWait):
		a.log.Error("the tunnel did not stop in time")
	}
}

// controller is libxray's Controller as the engine's Core: Xray creates
// the adapter itself, so there is no descriptor to pass.
type controller struct{ c *libxray.Controller }

func (c controller) Start(config string) error { return c.c.Start(config, 0) }
func (c controller) Stop() error               { return c.c.Stop() }

type realClock struct{}

func (realClock) Now() time.Time { return time.Now() }

func (realClock) AfterFunc(d time.Duration, f func()) func() bool {
	return time.AfterFunc(d, f).Stop
}
