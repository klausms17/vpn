// Command KirovVPNService is the Kirov VPN Windows service. Started by the
// Service Control Manager it runs the service; the installer runs it with
// "install", "stop" or "uninstall".
package main

import (
	"fmt"
	"os"

	"github.com/klausms17/vpn/windows/internal/service"
	"github.com/klausms17/vpn/windows/internal/winsys"
	"golang.org/x/sys/windows/svc"
)

// version is set by the build: -X main.version=1.0.N.
var version = "dev"

func main() {
	if inService, err := svc.IsWindowsService(); err == nil && inService {
		if err := service.Run(version); err != nil {
			os.Exit(1)
		}
		return
	}
	if len(os.Args) != 2 {
		usage()
	}
	if !winsys.Elevated() {
		fail(fmt.Errorf("нужны права администратора"))
	}
	var err error
	switch os.Args[1] {
	case "install":
		var exe string
		if exe, err = os.Executable(); err == nil {
			err = service.Install(exe)
		}
	case "stop":
		err = service.Stop()
	case "uninstall":
		err = service.Uninstall()
	default:
		usage()
	}
	if err != nil {
		fail(err)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "KirovVPNService install | stop | uninstall")
	os.Exit(2)
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "KirovVPNService:", err)
	os.Exit(1)
}
