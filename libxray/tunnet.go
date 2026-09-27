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

// AppleTunConfig is TunConfig in the shape NEPacketTunnelNetworkSettings
// takes: routes to exclude instead of routes to include for IPv4, masks
// instead of prefix lengths.
type AppleTunConfig struct {
	MTU        int          `json:"mtu"`
	IPv4       AppleRoute   `json:"ipv4"`         // the interface address
	IPv4Out    []AppleRoute `json:"ipv4Excluded"` // everything else goes in
	IPv6       string       `json:"ipv6"`         // the interface address
	IPv6Prefix int          `json:"ipv6Prefix"`
	IPv6In     []AppleRoute `json:"ipv6Included"` // global unicast only
	DNSServers []string     `json:"dnsServers"`
}

// AppleRoute is an IPv4 route as address and mask, or an IPv6 one as
// address and prefix length.
type AppleRoute struct {
	Address string `json:"address"`
	Mask    string `json:"mask,omitempty"`
	Prefix  int    `json:"prefix,omitempty"`
}

// TunSettingsApple returns the tunnel settings for an iOS packet tunnel
// as JSON: the same addresses, MTU, DNS and bypassed networks as
// TunSettings. The MTU must match the tun inbound (Xray reads MTU+4 bytes).
func TunSettingsApple(ipv6 bool) string {
	_ = ipv6 // IPv6 always goes in, so it cannot leak (as on Android)
	cfg := AppleTunConfig{
		MTU:        TunMTU,
		IPv4:       AppleRoute{Address: TunIPv4, Mask: ipv4Mask(TunIPv4Len)},
		IPv6:       TunIPv6,
		IPv6Prefix: TunIPv6Len,
		IPv6In:     []AppleRoute{routeV6(tunIPv6Route)},
		DNSServers: []string{TunDNSv4},
	}
	for _, s := range bypassIPv4 {
		p := netip.MustParsePrefix(s)
		// 0.0.0.0/8 is not a destination; iOS rejects it as a route.
		if p.Addr().IsUnspecified() {
			continue
		}
		cfg.IPv4Out = append(cfg.IPv4Out, AppleRoute{Address: p.Masked().Addr().String(), Mask: ipv4Mask(p.Bits())})
	}
	out, _ := json.Marshal(cfg)
	return string(out)
}

func ipv4Mask(bits int) string {
	var m [4]byte
	for i := 0; i < bits; i++ {
		m[i/8] |= 0x80 >> (i % 8)
	}
	return netip.AddrFrom4(m).String()
}

func routeV6(s string) AppleRoute {
	p := netip.MustParsePrefix(s)
	return AppleRoute{Address: p.Masked().Addr().String(), Prefix: p.Bits()}
}
