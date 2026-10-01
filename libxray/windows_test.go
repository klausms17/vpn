package libxray

import (
	"encoding/json"
	"reflect"
	"testing"

	xnet "github.com/xtls/xray-core/common/net"
)

func tunInbound(t *testing.T, cfg string) map[string]any {
	t.Helper()
	var c map[string]any
	if err := json.Unmarshal([]byte(cfg), &c); err != nil {
		t.Fatal(err)
	}
	for _, x := range c["inbounds"].([]any) {
		if ib := x.(map[string]any); ib["protocol"] == "tun" {
			return ib["settings"].(map[string]any)
		}
	}
	t.Fatal("no tun inbound")
	return nil
}

func TestWindowsTunInbound(t *testing.T) {
	useTrimmedGeo(t)
	p := realityProfile(t)
	android := tunInbound(t, buildOpts(t, BuildOptions{Outbounds: p.Outbounds, Tun: true}))
	if want := map[string]any{"name": "tun0", "mtu": float64(TunMTU)}; !reflect.DeepEqual(android, want) {
		t.Errorf("Android tun settings changed: %v", android)
	}

	cfg := buildOpts(t, BuildOptions{Outbounds: p.Outbounds, Tun: true, Windows: true})
	if err := ValidateConfig(cfg); err != nil {
		t.Fatalf("%v\n%s", err, cfg)
	}
	s := tunInbound(t, cfg)
	strs := func(v any) []string {
		var out []string
		for _, x := range v.([]any) {
			out = append(out, x.(string))
		}
		return out
	}
	if s["name"] != "Kirov VPN" || s["desc"] != "Kirov VPN" || s["mtu"] != float64(TunMTU) {
		t.Errorf("adapter: %v", s)
	}
	if got := strs(s["gateway"]); !reflect.DeepEqual(got, []string{"198.18.0.1/30"}) {
		t.Errorf("gateway %v", got)
	}
	if got := strs(s["dns"]); !reflect.DeepEqual(got, []string{TunDNSv4}) {
		t.Errorf("dns %v", got)
	}
	// Android's IPv4 routes, and no IPv6 at all: "misconfigtun" blocks it
	// outside the tunnel instead.
	if got := strs(s["autoSystemRoutingTable"]); !reflect.DeepEqual(got, complementIPv4(bypassIPv4)) {
		t.Errorf("routes %v", got)
	}
	if s["autoOutboundsInterface"] != "" {
		t.Errorf("Xray's own binder must stay off: %v", s["autoOutboundsInterface"])
	}
	if got := strs(s["autoSystemWfpBlockLeak"]); !reflect.DeepEqual(got, []string{"dns", "misconfigtun"}) {
		t.Errorf("leak filters %v", got)
	}
}

func TestWindowsRouting(t *testing.T) {
	useTrimmedGeo(t)
	p := realityProfile(t)
	tcp, udp := xnet.Network_TCP, xnet.Network_UDP
	inst := func(windows bool) func(domain, ip string, port uint16, network xnet.Network) string {
		in, err := newInstance(buildOpts(t, BuildOptions{Outbounds: p.Outbounds, Tun: true, Windows: windows}))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { in.Close() })
		return func(domain, ip string, port uint16, network xnet.Network) string {
			return route(t, in, tunInboundTag, domain, ip, port, network)
		}
	}
	win, android := inst(true), inst(false)
	for _, c := range []struct {
		domain, ip string
		port       uint16
		net        xnet.Network
		want       string
	}{
		{"", TunDNSv4, 53, udp, dnsOutTag},                  // DNS from programs, as on Android
		{"", "198.18.0.3", 138, udp, blockTag},              // NetBIOS broadcast on the tunnel's subnet
		{"", "224.0.0.251", 5353, udp, blockTag},            // mDNS
		{"", "224.0.0.252", 5355, udp, blockTag},            // LLMNR
		{"", "255.255.255.255", 137, udp, blockTag},         // broadcast
		{"", "93.184.215.14", 137, udp, blockTag},           // NetBIOS to anyone
		{"www.msftconnecttest.com", "", 80, tcp, DirectTag}, // Windows' connectivity check
		{"dns.msftncsi.com", "", 80, tcp, DirectTag},
		{"example.com", "93.184.215.14", 443, tcp, ProxyTag}, // the rest as on Android
		{"yandex.ru", "77.88.55.242", 443, tcp, DirectTag},
	} {
		if got := win(c.domain, c.ip, c.port, c.net); got != c.want {
			t.Errorf("%s %s:%d -> %s, want %s", c.domain, c.ip, c.port, got, c.want)
		}
	}
	if got := android("www.msftconnecttest.com", "", 80, tcp); got != ProxyTag {
		t.Errorf("Android routing changed: connectivity check -> %s", got)
	}
}

func TestWindowsResolvesServerNamesLocally(t *testing.T) {
	dnsAndProxy := func(o BuildOptions) (first map[string]any, sockopt any) {
		cfg, err := buildConfig(&o)
		if err != nil {
			t.Fatal(err)
		}
		first = cfg["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any)
		proxy := cfg["outbounds"].([]any)[0].(map[string]any)
		return first, dig(proxy, "streamSettings", "sockopt")
	}
	byName := []json.RawMessage{json.RawMessage(`{"protocol":"vless","settings":{"vnext":[{"address":"VPN.example.com","port":443,"users":[{"id":"x"}]}]}}`)}
	byIP := []json.RawMessage{json.RawMessage(`{"protocol":"vless","settings":{"vnext":[{"address":"203.0.113.10","port":443,"users":[{"id":"x"}]}]}}`)}

	first, sockopt := dnsAndProxy(BuildOptions{Outbounds: byName, Tun: true, Windows: true})
	want := map[string]any{
		"address":      "localhost",
		"domains":      []string{"full:vpn.example.com", "domain:msftconnecttest.com", "domain:msftncsi.com"},
		"skipFallback": true,
	}
	if !reflect.DeepEqual(first, want) {
		t.Errorf("first DNS server %v", first)
	}
	if !reflect.DeepEqual(sockopt, map[string]any{"domainStrategy": "UseIPv4"}) {
		t.Errorf("sockopt %v", sockopt)
	}
	if _, sockopt := dnsAndProxy(BuildOptions{Outbounds: byName, Tun: true, Windows: true, IPv6: true}); !reflect.DeepEqual(sockopt, map[string]any{"domainStrategy": "UseIP"}) {
		t.Errorf("with IPv6: sockopt %v", sockopt)
	}

	first, sockopt = dnsAndProxy(BuildOptions{Outbounds: byIP, Tun: true, Windows: true})
	if got := first["domains"]; !reflect.DeepEqual(got, windowsDirectDomains) {
		t.Errorf("server given by IP: names %v", got)
	}
	if sockopt != nil {
		t.Errorf("server given by IP: sockopt %v", sockopt)
	}

	first, sockopt = dnsAndProxy(BuildOptions{Outbounds: byName, Tun: true})
	if got := first["domains"]; !reflect.DeepEqual(got, []string{"geosite:private", "full:my.keenetic.net", "domain:routerlogin.net"}) {
		t.Errorf("Android DNS changed: first server %v", first)
	}
	if sockopt != nil {
		t.Errorf("Android outbound changed: sockopt %v", sockopt)
	}
}

func TestResolveServersLocally(t *testing.T) {
	var obs []any
	for _, s := range []string{
		// The root reaches its server through the hop: its name stays for
		// the hop to resolve.
		`{"tag":"proxy","settings":{"address":"exit.example.com","port":443},"streamSettings":{"sockopt":{"dialerProxy":"hop"}}}`,
		`{"tag":"hop","settings":{"servers":[{"address":"hop.example.com","port":443}]},"StreamSettings":{"network":"tcp"}}`,
		`{"tag":"pinned","settings":{"vnext":[{"address":"own.example.com","port":443}]},"streamSettings":{"sockopt":{"domainStrategy":"AsIs"}}}`,
	} {
		var ob map[string]any
		if err := json.Unmarshal([]byte(s), &ob); err != nil {
			t.Fatal(err)
		}
		obs = append(obs, ob)
	}
	names := resolveServersLocally(obs, "UseIPv4")
	if want := []string{"full:hop.example.com", "full:own.example.com"}; !reflect.DeepEqual(names, want) {
		t.Errorf("names %v, want %v", names, want)
	}
	if got := dig(obs[0].(map[string]any), "streamSettings", "sockopt", "domainStrategy"); got != nil {
		t.Errorf("chained root got a strategy: %v", got)
	}
	hop := obs[1].(map[string]any)
	if _, dup := hop["streamSettings"]; dup {
		t.Error("a second streamSettings key next to StreamSettings")
	}
	if got := dig(hop, "StreamSettings", "sockopt", "domainStrategy"); got != "UseIPv4" {
		t.Errorf("hop strategy %v", got)
	}
	if got := dig(obs[2].(map[string]any), "streamSettings", "sockopt", "domainStrategy"); got != "AsIs" {
		t.Errorf("a strategy set by the subscription was replaced: %v", got)
	}
}

func TestServerHost(t *testing.T) {
	for _, c := range []struct{ ob, want string }{
		{`{"settings":{"address":"a.example.com","port":443}}`, "a.example.com"},
		{`{"settings":{"vnext":[{"address":"b.example.com"}]}}`, "b.example.com"},
		{`{"Settings":{"Servers":[{"Address":"c.example.com"}]}}`, "c.example.com"},
		{`{"settings":{"peers":[{"endpoint":"wg.example.com:51820"}]}}`, "wg.example.com"},
		{`{"settings":{"vnext":[{"address":"203.0.113.10"}]}}`, ""},
		{`{"settings":{"address":"[2001:db8::1]"}}`, ""},
		{`{"protocol":"freedom"}`, ""},
	} {
		var ob map[string]any
		if err := json.Unmarshal([]byte(c.ob), &ob); err != nil {
			t.Fatal(err)
		}
		if got := serverHost(ob); got != c.want {
			t.Errorf("%s: %q, want %q", c.ob, got, c.want)
		}
	}
}
