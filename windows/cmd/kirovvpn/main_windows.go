// Command KirovVPN is the Kirov VPN tray icon and window, one per signed-in
// user. It only talks to the service (KirovVPNService).
package main

import (
	"os"
	"slices"

	"github.com/klausms17/vpn/windows/internal/ui"
	"golang.org/x/sys/windows"
)

// version is set by the build: -X main.version=1.0.N.
var version = "dev"

func main() {
	if err := ui.Run(version, os.Args[1:]); err != nil {
		// The self-test runs where nobody could close a message box.
		if !slices.Contains(os.Args[1:], "--selftest") {
			text, _ := windows.UTF16PtrFromString("Kirov VPN не запустился: " + err.Error())
			title, _ := windows.UTF16PtrFromString("Kirov VPN")
			windows.MessageBox(0, text, title, windows.MB_OK|windows.MB_ICONERROR)
		}
		os.Exit(1)
	}
}
