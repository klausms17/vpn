package service

import (
	"cmp"
	"fmt"
	"net/netip"
	"slices"
	"strings"
)

// routeTable is Windows' IPv4 routing as the conflict check reads it.
type routeTable struct {
	// tunnel is the LUID of the tunnel's adapter.
	tunnel   uint64
	routes   []route
	adapters map[uint64]netAdapter
}

type route struct {
	prefix netip.Prefix
	luid   uint64
	// metric is the route's plus its interface's: what Windows compares
	// between routes of the same length.
	metric uint32
	// local: Windows made it for an address of the adapter (its subnet,
	// the address itself, broadcast, multicast).
	local bool
}

type netAdapter struct {
	name, description string
	up                bool
	// physical: a network card, Ethernet or Wi-Fi, whose routes lead into
	// a local network.
	physical bool
	loopback bool
}

// conflict is an adapter, another VPN's, whose routes take traffic that
// the tunnel's routes are meant to carry.
type conflict struct {
	adapter string
	// product is the program, when the adapter tells it; "" otherwise.
	product string
	routes  int
	// share is the part of the tunnel's addresses it takes.
	share float64
}

// takeover is what other VPNs take of the tunnel's traffic, the adapter
// that takes most first.
type takeover struct {
	adapters []conflict
	// most: together they take most of what the tunnel carries, not only
	// some sites.
	most bool
}

// notInternet are the networks no VPN carries for the user: local,
// carrier-grade NAT, link-local, reserved and multicast ones, and the
// benchmarking range of the tunnel's own address, where other VPNs keep
// their fake addresses.
var notInternet = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("127.0.0.0/8"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("224.0.0.0/4"),
	netip.MustParsePrefix("240.0.0.0/4"),
}

// findConflicts finds the routes of other connected adapters that Windows
// prefers to the tunnel's for some addresses: longer than the tunnel's
// route that covers them, or as long and not costlier. Left out are
// network cards, whose routes lead to the LAN, and routes to one address,
// a server's (a cloud's metadata service, a VPN's own server). ok is false
// while the tunnel has no routes yet, as it starts or restarts.
func findConflicts(t routeTable) (found takeover, ok bool) {
	var ours []route
	var space float64
	for _, r := range t.routes {
		if r.luid == t.tunnel && !r.local && r.prefix.Addr().Is4() {
			ours = append(ours, r)
			space += size(r.prefix)
		}
	}
	if len(ours) == 0 {
		return takeover{}, false
	}
	byAdapter := map[uint64]*conflict{}
	var taken float64
	for _, r := range t.routes {
		a, known := t.adapters[r.luid]
		if r.luid == t.tunnel || !known || !a.up || a.physical || a.loopback || r.local ||
			!r.prefix.Addr().Is4() || r.prefix.Bits() == 32 || within(r.prefix, notInternet) || !wins(r, ours) {
			continue
		}
		c := byAdapter[r.luid]
		if c == nil {
			c = &conflict{adapter: cmp.Or(a.name, a.description), product: productOf(a.name, a.description)}
			byAdapter[r.luid] = c
		}
		c.routes++
		c.share += size(r.prefix) / space
		taken += size(r.prefix)
	}
	for _, c := range byAdapter {
		found.adapters = append(found.adapters, *c)
	}
	slices.SortFunc(found.adapters, func(a, b conflict) int {
		return cmp.Or(cmp.Compare(b.share, a.share), strings.Compare(a.adapter, b.adapter))
	})
	found.most = taken > space/2
	return found, true
}

// wins tells whether Windows sends r's addresses along r rather than along
// the tunnel's route that covers them. A shorter route, as another VPN's
// 0.0.0.0/1 or default route, loses to every route of the tunnel within it.
func wins(r route, ours []route) bool {
	var cover *route
	for i := range ours {
		o := &ours[i]
		if o.prefix.Bits() <= r.prefix.Bits() && o.prefix.Contains(r.prefix.Addr()) &&
			(cover == nil || o.prefix.Bits() > cover.prefix.Bits()) {
			cover = o
		}
	}
	return cover != nil && (r.prefix.Bits() > cover.prefix.Bits() || r.metric <= cover.metric)
}

func within(p netip.Prefix, nets []netip.Prefix) bool {
	for _, n := range nets {
		if n.Bits() <= p.Bits() && n.Contains(p.Addr()) {
			return true
		}
	}
	return false
}

func size(p netip.Prefix) float64 { return float64(uint64(1) << (32 - p.Bits())) }

// products are VPN programs whose adapters tell them by a word in their
// name or description, the programs before the protocols they run.
var products = []struct{ word, name string }{
	{"browbox", "Browsec"},
	{"browsec", "Browsec"},
	{"happ", "Happ"},
	{"hiddify", "Hiddify"},
	{"v2rayn", "v2rayN"},
	{"nekobox", "NekoBox"},
	{"nekoray", "NekoRay"},
	{"clash", "Clash"},
	{"mihomo", "Clash"},
	{"amnezia", "AmneziaVPN"},
	{"outline", "Outline"},
	{"cloudflare", "Cloudflare WARP"},
	{"kaspersky", "Kaspersky VPN"},
	{"adguard", "AdGuard VPN"},
	{"proton", "Proton VPN"},
	{"nordlynx", "NordVPN"},
	{"nordvpn", "NordVPN"},
	{"expressvpn", "ExpressVPN"},
	{"windscribe", "Windscribe"},
	{"wireguard", "WireGuard"},
	{"openvpn", "OpenVPN"},
	{"tap-windows", "OpenVPN"},
}

// productOf names the program an adapter belongs to, or "".
func productOf(name, description string) string {
	text := strings.ToLower(name + " " + description)
	for _, p := range products {
		if strings.Contains(text, p.word) {
			return p.name
		}
	}
	return ""
}

// The notice names at most maxNamed adapters, each name cut to
// maxAdapterName characters (Windows allows 256).
const (
	maxNamed       = 3
	maxAdapterName = 32
)

// notice is what the window shows while other VPNs take the traffic, ""
// when none does.
func (t takeover) notice() string {
	if len(t.adapters) == 0 {
		return ""
	}
	var named []string
	for _, c := range t.adapters[:min(len(t.adapters), maxNamed)] {
		part := "адаптер «" + shorten(c.adapter, maxAdapterName) + "»"
		if c.product != "" {
			part = c.product + " (" + part + ")"
		}
		named = append(named, part)
	}
	list := strings.Join(named, ", ")
	if more := len(t.adapters) - maxNamed; more > 0 {
		list += fmt.Sprintf(" и ещё %d", more)
	}
	one := len(t.adapters) == 1
	switch {
	case t.most && one:
		return "Трафик перехватывает другой VPN — " + list + ". Выключите его: пока он включён, Kirov VPN не работает."
	case t.most:
		return "Трафик перехватывают другие VPN — " + list + ". Выключите их: пока они включены, Kirov VPN не работает."
	case one:
		return "Часть сайтов перехватывает другой VPN — " + list + ": они идут мимо Kirov VPN. Если они нужны через Kirov VPN, выключите его."
	}
	return "Часть сайтов перехватывают другие VPN — " + list + ": они идут мимо Kirov VPN. Если они нужны через Kirov VPN, выключите их."
}

// logLine is the journal's line about it, without addresses.
func (t takeover) logLine() string {
	if len(t.adapters) == 0 {
		return "no other VPN takes the tunnel's traffic now"
	}
	parts := make([]string, len(t.adapters))
	for i, c := range t.adapters {
		parts[i] = fmt.Sprintf("%q (%s): routes %d, %.1f%% of the tunnel's addresses",
			shorten(c.adapter, maxAdapterName), cmp.Or(c.product, "unknown"), c.routes, 100*c.share)
	}
	return "another VPN takes the tunnel's traffic: " + strings.Join(parts, "; ")
}

// shorten cuts s to n characters, the last of them "…".
func shorten(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return strings.TrimRight(string(r[:n-1]), " -_.") + "…"
	}
	return s
}
