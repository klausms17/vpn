package libxray

import (
	"encoding/json"
	"net/netip"
	"sort"
)

// TUN interface parameters. 198.18.0.0/15 is reserved for benchmarking
// (RFC 2544), so it never collides with a real LAN or carrier network.
const (
	TunMTU       = 1500
	TunIPv4      = "198.18.0.1"
	TunIPv4Len   = 30
	TunDNSv4     = "198.18.0.2"
	TunIPv6      = "fdfe:dcba:9876::1"
	TunIPv6Len   = 126
	tunIPv6Route = "2000::/3" // all global unicast IPv6
)

// Networks that stay outside the VPN: LAN, loopback, link-local, CGNAT,
// multicast and reserved space. Keeping them out of the tunnel lets local
// devices (printers, casting, routers) keep working and avoids needing the
// Android 17 local-network permission for traffic we would only pass
// through anyway.
var bypassIPv4 = []string{
	"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
	"172.16.0.0/12", "192.0.0.0/24", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4",
}

// TunConfig describes how the platform should set up its TUN interface.
type TunConfig struct {
	MTU        int      `json:"mtu"`
	Addresses  []string `json:"addresses"` // "ip/prefix"
	Routes     []string `json:"routes"`    // "ip/prefix"
	DNSServers []string `json:"dnsServers"`
}

// TunSettings returns TunConfig as JSON.
func TunSettings(ipv6 bool) string {
	routes := complementIPv4(bypassIPv4)
	routes = append(routes, tunIPv6Route) // always capture IPv6, so it can't leak
	cfg := TunConfig{
		MTU:        TunMTU,
		Addresses:  []string{netip.PrefixFrom(netip.MustParseAddr(TunIPv4), TunIPv4Len).String(), netip.PrefixFrom(netip.MustParseAddr(TunIPv6), TunIPv6Len).String()},
		Routes:     routes,
		DNSServers: []string{TunDNSv4},
	}
	out, _ := json.Marshal(cfg)
	return string(out)
}

// complementIPv4 returns the minimal list of prefixes covering 0.0.0.0/0
// except the given prefixes.
func complementIPv4(exclude []string) []string {
	var ex []netip.Prefix
	for _, s := range exclude {
		ex = append(ex, netip.MustParsePrefix(s).Masked())
	}
	var out []string
	var walk func(p netip.Prefix)
	walk = func(p netip.Prefix) {
		fullyExcluded, overlaps := false, false
		for _, e := range ex {
			if e.Bits() <= p.Bits() && e.Contains(p.Addr()) {
				fullyExcluded = true
				break
			}
			if p.Bits() < e.Bits() && p.Contains(e.Addr()) {
				overlaps = true
			}
		}
		switch {
		case fullyExcluded:
			return
		case !overlaps:
			out = append(out, p.String())
			return
		}
		// Split into two halves.
		bits := p.Bits() + 1
		lo := netip.PrefixFrom(p.Addr(), bits)
		a := p.Addr().As4()
		idx := p.Bits()
		a[idx/8] |= 0x80 >> (idx % 8)
		hi := netip.PrefixFrom(netip.AddrFrom4(a), bits)
		walk(lo)
		walk(hi)
	}
	walk(netip.MustParsePrefix("0.0.0.0/0"))
	sort.Strings(out)
	return out
}
