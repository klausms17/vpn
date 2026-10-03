package libxray

import (
	"net/netip"
	"strings"
)

// The Windows app runs Xray's tun inbound on wintun: Xray creates the
// adapter itself and applies the addresses, routes and DNS that Android's
// VpnService gets from TunSettings (docs/windows/PLAN.md, section 2.4).
const windowsAdapter = "Kirov VPN"

// windowsDirectDomains always go directly. Windows checks its internet
// connection (NCSI) through them, and must not mark the PC offline while
// the server is down and Russian sites still work.
var windowsDirectDomains = []string{"domain:msftconnecttest.com", "domain:msftncsi.com"}

// windowsTunSettings are the settings of the tun inbound on Windows. The
// tunnel carries IPv4 only: "misconfigtun" blocks IPv6 outside it, so an
// IPv6 connection fails at once instead of being accepted by gVisor and
// reset, which keeps programs falling back to IPv4.
func windowsTunSettings(mtu int) map[string]any {
	return map[string]any{
		"name":                   windowsAdapter,
		"desc":                   windowsAdapter,
		"mtu":                    mtu,
		"gateway":                []string{tunPrefix().String()},
		"dns":                    []string{TunDNSv4},
		"autoSystemRoutingTable": complementIPv4(bypassIPv4),
		// The app binds the core's sockets itself: Xray's binder prefers
		// Wi-Fi over a cheaper Ethernet route and keeps binding after Stop.
		"autoOutboundsInterface": "",
		"autoSystemWfpBlockLeak": []string{"dns", "misconfigtun"},
	}
}

// windowsRules keep what Windows itself sends on the tunnel's subnet
// (NetBIOS, mDNS, LLMNR, multicast and broadcast) away from the proxy, and
// send its connectivity checks directly.
func windowsRules() []rule {
	return []rule{
		{"ip": []string{tunPrefix().Masked().String(), "224.0.0.0/4", "255.255.255.255/32"}, "outboundTag": blockTag},
		{"network": "udp", "port": "137-138,5353,5355", "outboundTag": blockTag},
		{"domain": windowsDirectDomains, "outboundTag": DirectTag},
	}
}

func tunPrefix() netip.Prefix {
	return netip.PrefixFrom(netip.MustParseAddr(TunIPv4), TunIPv4Len)
}

// resolveServersLocally has Xray's DNS module resolve the names of the
// servers the profile dials itself, through the "localhost" entry that
// buildDNS adds for them, with its cache. Without it Xray would ask Go's
// resolver again for every connection to the server. It returns those
// names as DNS domain rules. Hops reached through another outbound keep
// their names, which the hop before them resolves.
func resolveServersLocally(outbounds []any, strategy string) []string {
	var names []string
	for _, x := range outbounds {
		ob, _ := x.(map[string]any)
		host := serverHost(ob)
		if host == "" {
			continue
		}
		sockopt := childMap(childMap(ob, "streamSettings"), "sockopt")
		if proxy, _ := getFold(sockopt, "dialerProxy").(string); proxy != "" {
			continue
		}
		if s, _ := getFold(sockopt, "domainStrategy").(string); s == "" {
			deleteFold(sockopt, "domainStrategy")
			sockopt["domainStrategy"] = strategy
		}
		names = append(names, "full:"+strings.ToLower(host))
	}
	return names
}

// serverHost is the domain name of the server an outbound connects to, or
// "" for an IP address or an outbound without a server.
func serverHost(ob map[string]any) string {
	settings, _ := getFold(ob, "settings").(map[string]any)
	host, _ := getFold(settings, "address").(string)
	for _, key := range []string{"vnext", "servers", "peers"} {
		if host != "" {
			break
		}
		list, _ := getFold(settings, key).([]any)
		if len(list) == 0 {
			continue
		}
		server, _ := list[0].(map[string]any)
		host, _ = getFold(server, "address").(string)
		if ep, _ := getFold(server, "endpoint").(string); host == "" && ep != "" {
			host, _, _ = splitHostPort(ep)
		}
	}
	host = strings.Trim(strings.TrimSpace(host), "[]")
	if _, err := netip.ParseAddr(host); err == nil {
		return ""
	}
	return host
}

// childMap returns m[key] (matched like Xray matches keys) as a map,
// creating it when it is missing.
func childMap(m map[string]any, key string) map[string]any {
	if child, ok := getFold(m, key).(map[string]any); ok {
		return child
	}
	child := map[string]any{}
	deleteFold(m, key)
	m[key] = child
	return child
}
