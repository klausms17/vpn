package service

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"

	"github.com/klausms17/vpn/libxray"
)

// The adapters of the tests, by LUID.
const (
	tunLUID = iota + 1
	browsecLUID
	wireguardLUID
	wifiLUID
	downLUID
	loopbackLUID
	radminLUID
)

// browsecName is the name of Browsec's adapter as Windows showed it.
const browsecName = "browbox-tun-1b4e28ba-2fa1-11d2-883f-0016d3cca427"

var testAdapters = map[uint64]netAdapter{
	tunLUID:       {name: "Kirov VPN", description: "Kirov VPN Tunnel", up: true},
	browsecLUID:   {name: browsecName, description: "sing-tun Tunnel", up: true},
	wireguardLUID: {name: "wg0", description: "WireGuard Tunnel", up: true},
	wifiLUID:      {name: "Беспроводная сеть", description: "Intel(R) Wi-Fi 6 AX201 160MHz", up: true, physical: true},
	downLUID:      {name: "Other VPN", description: "Wintun Userspace Tunnel"},
	loopbackLUID:  {name: "Loopback Pseudo-Interface 1", up: true, loopback: true},
	radminLUID:    {name: "Radmin VPN", description: "Famatech Radmin VPN Ethernet Adapter", up: true},
}

// tunnelRoutes are the tunnel's routes as Xray sets them on Windows,
// Android's list, with metric 0, and Windows' own for its address.
func tunnelRoutes(t *testing.T) []route {
	var c libxray.TunConfig
	if err := json.Unmarshal([]byte(libxray.TunSettings(false)), &c); err != nil {
		t.Fatal(err)
	}
	var routes []route
	for _, s := range c.Routes {
		if p := netip.MustParsePrefix(s); p.Addr().Is4() {
			routes = append(routes, route{prefix: p, luid: tunLUID})
		}
	}
	for _, s := range []string{"198.18.0.0/30", "198.18.0.1/32", "198.18.0.3/32", "224.0.0.0/4", "255.255.255.255/32"} {
		routes = append(routes, route{prefix: netip.MustParsePrefix(s), luid: tunLUID, local: true})
	}
	return routes
}

func on(luid uint64, metric uint32, prefixes ...string) []route {
	var routes []route
	for _, s := range prefixes {
		routes = append(routes, route{prefix: netip.MustParsePrefix(s), luid: luid, metric: metric})
	}
	return routes
}

// halves splits each route of the tunnel in two, each one bit longer: a
// VPN that takes everything the tunnel carries.
func halves(routes []route, luid uint64) []route {
	var out []route
	for _, r := range routes {
		if r.local {
			continue
		}
		lo := netip.PrefixFrom(r.prefix.Addr(), r.prefix.Bits()+1)
		a := r.prefix.Addr().As4()
		a[r.prefix.Bits()/8] |= 0x80 >> (r.prefix.Bits() % 8)
		hi := netip.PrefixFrom(netip.AddrFrom4(a), r.prefix.Bits()+1)
		out = append(out, route{prefix: lo, luid: luid}, route{prefix: hi, luid: luid})
	}
	return out
}

func TestFindConflicts(t *testing.T) {
	ours := tunnelRoutes(t)
	type want struct{ adapter, product string }
	for _, c := range []struct {
		name   string
		routes []route
		want   []want
		most   bool
	}{
		{"Browsec takes all the tunnel carries", halves(ours, browsecLUID), []want{{browsecName, "Browsec"}}, true},
		{"Browsec's 88.0.0.0/6 within the tunnel's 64.0.0.0/3", on(browsecLUID, 0, "88.0.0.0/6", "2.0.0.0/12"), []want{{browsecName, "Browsec"}}, false},
		{"Browsec's own subnet and a 0.0.0.0/7 the tunnel's 1.0.0.0/8 beats", append(on(browsecLUID, 0, "0.0.0.0/7"),
			route{prefix: netip.MustParsePrefix("172.19.0.0/30"), luid: browsecLUID, local: true},
			route{prefix: netip.MustParsePrefix("172.19.0.1/32"), luid: browsecLUID, local: true}), nil, false},
		{"a LAN and an ISP's public subnet on Wi-Fi", on(wifiLUID, 25, "192.168.1.0/24", "95.165.0.0/22", "0.0.0.0/0"), nil, false},
		{"ranges the tunnel leaves out", on(wireguardLUID, 0, "100.64.0.0/10", "10.8.0.0/24", "169.254.0.0/16", "198.18.0.0/15", "239.255.255.250/32"), nil, false},
		{"a disconnected adapter", on(downLUID, 0, "88.0.0.0/6"), nil, false},
		{"broader routes", on(wireguardLUID, 0, "0.0.0.0/1", "128.0.0.0/1", "0.0.0.0/0"), nil, false},
		{"the tunnel's own route, as cheap", on(wireguardLUID, 0, "64.0.0.0/3"), []want{{"wg0", "WireGuard"}}, false},
		{"the tunnel's own route, costlier", on(wireguardLUID, 5, "64.0.0.0/3"), nil, false},
		{"Windows' own subnet of a LAN VPN", []route{{prefix: netip.MustParsePrefix("26.0.0.0/8"), luid: radminLUID, local: true}}, nil, false},
		{"loopback", on(loopbackLUID, 0, "127.0.0.0/8", "1.2.3.4/32"), nil, false},
		{"a route to one server", on(wireguardLUID, 0, "168.63.129.16/32"), nil, false},
		{"an adapter Windows does not list", on(99, 0, "88.0.0.0/6"), nil, false},
		{"two VPNs, the one taking more first", append(on(wireguardLUID, 0, "8.8.8.0/24"), on(browsecLUID, 0, "88.0.0.0/6")...),
			[]want{{browsecName, "Browsec"}, {"wg0", "WireGuard"}}, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			found, ok := findConflicts(routeTable{tunnel: tunLUID, routes: append(append([]route{}, ours...), c.routes...), adapters: testAdapters})
			if !ok {
				t.Fatal("not known")
			}
			var got []want
			for _, a := range found.adapters {
				got = append(got, want{a.adapter, a.product})
			}
			if len(got) != len(c.want) || found.most != c.most {
				t.Fatalf("%+v, most %v", got, found.most)
			}
			for i := range got {
				if got[i] != c.want[i] {
					t.Fatalf("%+v", got)
				}
			}
		})
	}
}

func TestFindConflictsUnknownWithoutTunnelRoutes(t *testing.T) {
	routes := append(on(browsecLUID, 0, "88.0.0.0/6"), route{prefix: netip.MustParsePrefix("198.18.0.0/30"), luid: tunLUID, local: true})
	if _, ok := findConflicts(routeTable{tunnel: tunLUID, routes: routes, adapters: testAdapters}); ok {
		t.Error("known while the tunnel has no routes")
	}
	if _, ok := findConflicts(routeTable{}); ok {
		t.Error("known without a table")
	}
}

func TestProductOf(t *testing.T) {
	for _, c := range []struct{ name, description, want string }{
		{browsecName, "sing-tun Tunnel", "Browsec"},
		{"Happ", "Happ Tunnel", "Happ"},
		{"NordLynx", "NordLynx Tunnel", "NordVPN"},
		{"outline-tap0", "TAP-Windows Adapter V9", "Outline"},
		{"Ethernet 3", "TAP-Windows Adapter V9", "OpenVPN"},
		{"AmneziaVPN", "AmneziaWG Tunnel", "AmneziaVPN"},
		{"CloudflareWARP", "Cloudflare WARP Interface Tunnel", "Cloudflare WARP"},
		{"Meta", "Meta Tunnel", ""},
	} {
		if got := productOf(c.name, c.description); got != c.want {
			t.Errorf("%s / %s: %q", c.name, c.description, got)
		}
	}
}

func TestConflictNotice(t *testing.T) {
	browsec := conflict{adapter: browsecName, product: "Browsec", routes: 104, share: 0.9}
	wg := conflict{adapter: "wg0", product: "WireGuard", routes: 1, share: 0.01}
	unknown := conflict{adapter: "Meta", routes: 2}
	for _, c := range []struct {
		name string
		t    takeover
		want string
	}{
		{"none", takeover{}, ""},
		{"Browsec takes it all", takeover{adapters: []conflict{browsec}, most: true},
			"Трафик перехватывает другой VPN — Browsec (адаптер «browbox-tun-1b4e28ba-2fa1-11d2…»). Выключите его: пока он включён, Kirov VPN не работает."},
		{"two take it all", takeover{adapters: []conflict{browsec, wg}, most: true},
			"Трафик перехватывают другие VPN — Browsec (адаптер «browbox-tun-1b4e28ba-2fa1-11d2…»), WireGuard (адаптер «wg0»). Выключите их: пока они включены, Kirov VPN не работает."},
		{"one takes some sites", takeover{adapters: []conflict{unknown}},
			"Часть сайтов перехватывает другой VPN — адаптер «Meta»: они идут мимо Kirov VPN. Если они нужны через Kirov VPN, выключите его."},
		{"many take some sites", takeover{adapters: []conflict{wg, unknown, wg, unknown, wg}},
			"Часть сайтов перехватывают другие VPN — WireGuard (адаптер «wg0»), адаптер «Meta», WireGuard (адаптер «wg0») и ещё 2: они идут мимо Kirov VPN. Если они нужны через Kirov VPN, выключите их."},
	} {
		if got := c.t.notice(); got != c.want {
			t.Errorf("%s:\n got %s\nwant %s", c.name, got, c.want)
		}
	}
}

func TestConflictLogHasNoAddresses(t *testing.T) {
	line := takeover{adapters: []conflict{{adapter: browsecName, product: "Browsec", routes: 104, share: 0.873}}, most: true}.logLine()
	if !strings.Contains(line, "Browsec") || !strings.Contains(line, "routes 104, 87.3%") || strings.ContainsAny(line, "/") {
		t.Errorf("%q", line)
	}
}
