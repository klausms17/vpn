// Command othervpn stands in for another VPN in CI's smoke test: a wintun
// adapter that has the tunnel's address until the program is stopped. With
// -up it runs a session, so the adapter is connected, as a working VPN's
// is; without, it is disconnected, as one left behind. It is never
// shipped, and needs wintun.dll next to it.
package main

import (
	"flag"
	"log"
	"net/netip"
	"os"
	"os/signal"

	"github.com/klausms17/vpn/libxray"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wintun"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

func main() {
	up := flag.Bool("up", false, "run a session, so the adapter is connected")
	ready := flag.String("ready", "", "a file written once the address is set")
	flag.Parse()
	if *ready == "" {
		log.Fatal("usage: othervpn [-up] -ready FILE")
	}
	adapter, err := wintun.CreateAdapter("Other VPN", "Other VPN", nil)
	if err != nil {
		log.Fatal(err)
	}
	defer adapter.Close()
	if *up {
		session, err := adapter.StartSession(0x400000)
		if err != nil {
			log.Fatal(err)
		}
		defer session.End()
	}
	luid := winipcfg.LUID(adapter.LUID())
	address := netip.PrefixFrom(netip.MustParseAddr(libxray.TunIPv4), libxray.TunIPv4Len)
	if err := luid.SetIPAddressesForFamily(windows.AF_INET, []netip.Prefix{address}); err != nil {
		log.Fatal(err)
	}
	if err := os.WriteFile(*ready, []byte("ready\n"), 0o600); err != nil {
		log.Fatal(err)
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt)
	<-stop
}
