// Command othervpn stands in for another VPN in CI's smoke test: a wintun
// adapter that lives until the program is stopped. With -up it runs a
// session, so the adapter is connected, as a working VPN's is; without, it
// is disconnected, as one left behind. It has the tunnel's address, or
// with -route an address of its own and a route through itself, as
// Browsec's adapter has. It is never shipped, and needs wintun.dll next
// to it.
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

// The adapter's own address with -route, and the gateway the route goes
// through: sing-tun's, as Browsec has them.
var (
	ownAddress = netip.MustParsePrefix("172.19.0.1/30")
	gateway    = netip.MustParseAddr("172.19.0.2")
)

func main() {
	up := flag.Bool("up", false, "run a session, so the adapter is connected")
	ready := flag.String("ready", "", "a file written once the address and route are set")
	name := flag.String("name", "Other VPN", "the adapter's name")
	kind := flag.String("type", "Other VPN", `the tunnel type, which wintun makes the description ("<type> Tunnel")`)
	through := flag.String("route", "", "a network to route through the adapter instead of taking the tunnel's address")
	flag.Parse()
	if *ready == "" {
		log.Fatal("usage: othervpn [-up] [-name NAME] [-type TYPE] [-route NETWORK] -ready FILE")
	}
	adapter, err := wintun.CreateAdapter(*name, *kind, nil)
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
	if *through != "" {
		address = ownAddress
	}
	if err := luid.SetIPAddressesForFamily(windows.AF_INET, []netip.Prefix{address}); err != nil {
		log.Fatal(err)
	}
	if *through != "" {
		network, err := netip.ParsePrefix(*through)
		if err != nil {
			log.Fatal(err)
		}
		if err := luid.AddRoute(network.Masked(), gateway, 0); err != nil {
			log.Fatal(err)
		}
	}
	if err := os.WriteFile(*ready, []byte("ready\n"), 0o600); err != nil {
		log.Fatal(err)
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt)
	<-stop
}
