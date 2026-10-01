package service

import (
	"context"
	"encoding/json"
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
	"golang.org/x/sys/windows"
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
	// trimEvery is how often the core's log is cut while it runs: weeks of
	// a tunnel would otherwise grow it without end.
	trimEvery = 10 * time.Minute
	// bootWindow: a service that starts this soon after Windows did was
	// started by the boot, not by an update or after a crash.
	bootWindow = 5 * time.Minute
	// logTail is how much of each log the journal shows, as on Android.
	logTail = 64 << 10
	// The server checks of the window, as Android's.
	pingURL       = "https://www.gstatic.com/generate_204"
	pingTimeoutMs = 10_000
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
	// Sealed too: the sites and programs say what the user does.
	settings := store.New(filepath.Join(dataDir, "settings.json"), defaultSettings,
		winsys.DPAPI{Name: "Kirov VPN settings"}, log.Warn)
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
		Core:     controller{c: libxray.NewController(), log: log.Info},
		Binder:   binder,
		Hold:     wfpHold{},
		Runtime:  runtime,
		Clock:    realClock{},
		Profiles: profiles.ReadStrict,
		Build:    buildConfig(xrayLog, settings.Read),
		TrimLog:  func() { tunnel.TrimLog(xrayLog, tunnel.LogMaxBytes, tunnel.LogKeepBytes) },
		Publish:  func(s ipc.Status) { server.Broadcast(ipc.NewEvent(ipc.EventStatus, s)) },
		Explain:  explainer{holder: addressHolder, ipv6Off: ipv6Off}.explain,
		Log:      log.Info,
	})
	h := &handler{
		tunnel:   eng,
		profiles: profiles,
		settings: settings,
		pinger:   newPinger(measure, func(p ipc.Pings) { server.Broadcast(ipc.NewEvent(ipc.EventPings, p)) }),
		keys: func(ctx context.Context, text string) ([]model.Key, []string, error) {
			return importer.Keys(ctx, text, importer.ForService)
		},
		site: libxray.UserRuleEntry,
		logs: func() ipc.Logs {
			return ipc.Logs{Sections: []ipc.LogSection{
				{Title: "Служба Kirov VPN", Text: applog.Tail(filepath.Join(logDir, "service.log"), logTail, false)},
				{Title: "Ядро Xray", Text: applog.Tail(xrayLog, logTail, true)},
			}}
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
	go func() {
		t := time.NewTicker(trimEvery)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				tunnel.TrimLog(xrayLog, tunnel.LogMaxBytes, tunnel.LogKeepBytes)
			}
		}
	}()
	if !settings.Read().AutoConnect && windows.DurationSinceBoot() < bootWindow {
		log.Info("not connecting at boot: switched off in the settings")
		runtime.SetShouldRun(false)
	}
	eng.Resume()
	return a, nil
}

// measure checks how fast a server answers, through a core of its own, as
// Android's ping does. The service runs it as SYSTEM, so the server's
// outbounds pass the same check as when the tunnel starts.
func measure(outbounds json.RawMessage) (int64, error) {
	var obs []json.RawMessage
	if err := json.Unmarshal(outbounds, &obs); err != nil {
		return 0, err
	}
	if err := importer.ForService(obs); err != nil {
		return 0, err
	}
	config, err := libxray.BuildProxyOnlyConfig(string(outbounds))
	if err != nil {
		return 0, err
	}
	return libxray.MeasureOutboundDelay(config, pingURL, pingTimeoutMs)
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
type controller struct {
	c   *libxray.Controller
	log func(string)
}

func (c controller) Start(config string) error {
	if err := claimTunAddress(c.log); err != nil {
		return err
	}
	return c.c.Start(config, 0)
}

func (c controller) Stop() error { return c.c.Stop() }

type realClock struct{}

func (realClock) Now() time.Time { return time.Now() }

func (realClock) AfterFunc(d time.Duration, f func()) func() bool {
	return time.AfterFunc(d, f).Stop
}
