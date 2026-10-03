package ui

import (
	"context"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"github.com/klausms17/vpn/libxray/client/applog"
	"github.com/klausms17/vpn/windows/assets"
	"github.com/klausms17/vpn/windows/internal/ipc"
	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"
	"golang.org/x/sys/windows"
)

// selftestWait bounds how long the self-test waits for the page.
const selftestWait = 90 * time.Second

var trayIcons = map[string][]byte{
	"off":        assets.TrayOff,
	"connecting": assets.TrayConnecting,
	"on":         assets.TrayOn,
	"error":      assets.TrayError,
}

// Run shows the tray icon, and the window unless args has "--tray" (the
// start at logon), and returns when the user quits. An "Add to Kirov VPN"
// link in args (the browser starts the app with it) waits for the user's
// answer in the window. With "--selftest" it only loads the page in a
// hidden window and exits, 0 once the page has loaded, for CI.
func Run(version string, args []string) error {
	local, err := windows.KnownFolderPath(windows.FOLDERID_LocalAppData, windows.KF_FLAG_DEFAULT)
	if err != nil {
		return err
	}
	dir := filepath.Join(local, "Kirov VPN")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	log := applog.New(filepath.Join(dir, "ui.log"))
	selftest := slices.Contains(args, "--selftest")
	hidden := selftest || slices.Contains(args, "--tray")
	log.Info(fmt.Sprintf("window %s starting", version))

	var (
		app  *application.App
		win  *application.WebviewWindow
		tray *application.SystemTray
	)
	link := newLink(func(s Snapshot) {
		app.Event.Emit("snapshot", s)
		look := lookOf(s)
		tray.SetIcon(trayIcons[look.icon])
		tray.SetTooltip(look.tooltip)
	})
	bridge := &Bridge{link: link, version: version, uiLog: filepath.Join(dir, "ui.log")}
	for _, a := range args {
		if _, ok := bridge.links.offer(a); ok {
			log.Info("started with an add link")
		}
	}
	if selftest {
		bridge.loaded = func() {
			log.Info("self-test: the page loaded")
			app.Quit()
		}
		time.AfterFunc(selftestWait, func() {
			log.Error("self-test: the page did not load")
			os.Exit(1)
		})
	}

	menu := &trayMenu{
		open: func() { show(win) },
		items: func() []*menuItem {
			look := lookOf(link.snapshot())
			quit := &menuItem{label: "Выход", enabled: true, action: app.Quit}
			if !look.connect {
				// Quitting must not leave a VPN running out of sight.
				quit = &menuItem{label: "Отключить VPN и выйти", enabled: true, action: func() {
					if err := bridge.Disconnect(); err != nil {
						log.Warn("tray: " + err.Error())
					}
					app.Quit()
				}}
			}
			return []*menuItem{
				{label: "Открыть Kirov VPN", enabled: true, action: func() { show(win) }},
				{label: look.action, enabled: look.enabled, action: func() {
					var err error
					if look.connect {
						err = bridge.Connect()
					} else {
						err = bridge.Disconnect()
					}
					if err != nil {
						log.Warn("tray: " + err.Error())
						show(win)
					}
				}},
				nil,
				quit,
			}
		},
	}

	page, err := fs.Sub(frontend, "frontend")
	if err != nil {
		return err
	}
	app = application.New(application.Options{
		Name:        "Kirov VPN",
		Description: "Kirov VPN",
		Icon:        assets.App,
		Services:    []application.Service{application.NewService(bridge)},
		Assets:      application.AssetOptions{Handler: application.AssetFileServerFS(page), Middleware: sameOrigin},
		Logger:      slog.New(slog.NewTextHandler(log, &slog.HandlerOptions{Level: slog.LevelWarn})),
		SingleInstance: &application.SingleInstanceOptions{
			UniqueID: "com.klausms.vpn.windows",
			// Started again (a shortcut, the Start menu, an add link in the
			// browser): show the window, asking about the link.
			OnSecondInstanceLaunch: func(data application.SecondInstanceData) {
				for _, a := range data.Args {
					if prompt, ok := bridge.links.offer(a); ok {
						log.Info("an add link came")
						app.Event.Emit("link", prompt)
					}
				}
				show(win)
			},
		},
		Windows: application.WindowsOptions{
			WndProcInterceptor:            menu.intercept,
			DisableQuitOnLastWindowClosed: true,
			// Not next to the exe: Program Files is not writable.
			WebviewUserDataPath: filepath.Join(dir, "WebView2"),
		},
	})
	bridge.copyText = app.Clipboard.SetText
	bridge.pickProgram = func() (string, error) {
		path, err := app.Dialog.OpenFile().
			SetTitle("Выберите программу").
			AddFilter("Программы", "*.exe").
			AttachToWindow(win).
			PromptForSingleSelection()
		// The dialog reports a cancel as an error of its own.
		if err != nil && strings.Contains(err.Error(), "cancel") {
			return "", nil
		}
		return path, err
	}
	win = app.Window.NewWithOptions(application.WebviewWindowOptions{
		Name:             "main",
		Title:            "Kirov VPN",
		Width:            1080,
		Height:           700,
		MinWidth:         900,
		MinHeight:        600,
		Hidden:           hidden,
		URL:              "/",
		BackgroundColour: application.NewRGB(0xF5, 0xF7, 0xFB),
	})
	// Closing the window leaves the app in the tray; «Выход» there quits.
	win.RegisterHook(events.Common.WindowClosing, func(e *application.WindowEvent) {
		e.Cancel()
		win.Hide()
	})

	tray = app.SystemTray.New()
	tray.SetIcon(assets.TrayError)
	tray.SetTooltip("Kirov VPN")
	tray.OnClick(func() { show(win) })

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app.Event.OnApplicationEvent(events.Common.ApplicationStarted, func(*application.ApplicationEvent) {
		go link.run(ctx, func(ctx context.Context) (io.ReadWriteCloser, error) {
			conn, err := ipc.Dial(ctx)
			if err != nil {
				return nil, err
			}
			return conn, nil
		})
	})
	return app.Run()
}

func show(win *application.WebviewWindow) {
	win.Show()
	win.Restore()
	win.Focus()
}
