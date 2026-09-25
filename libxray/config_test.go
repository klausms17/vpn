package libxray

import (
	"context"
	"encoding/json"
	"net/netip"
	"strings"
	"testing"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	routingsession "github.com/xtls/xray-core/features/routing/session"
)

func buildOpts(t *testing.T, o BuildOptions) string {
	t.Helper()
	in, _ := json.Marshal(o)
	cfg, err := BuildConfig(string(in))
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

func realityProfile(t *testing.T) *Profile {
	k := getKeys(t)
	return mustParse(t, "vless://"+k.UUID+"@203.0.113.10:443?type=tcp&security=reality&pbk="+k.RealityPub+"&sni=www.google.com&sid=ab&flow=xtls-rprx-vision#r")
}

func TestBuildConfigAllModesValidate(t *testing.T) {
	useTrimmedGeo(t)
	p := realityProfile(t)
	for _, mode := range []string{ModeRuDirect, ModeBlockedOnly, ModeGlobal} {
		for _, ipv6 := range []bool{false, true} {
			cfg := buildOpts(t, BuildOptions{
				Outbounds: p.Outbounds, Mode: mode, IPv6: ipv6, Tun: true,
				DirectRules: []string{"example.org", "10.1.0.0/16", "*.corp.example", "https://intranet.example.com/x", "bad domain!", "regexp:^ya\\.", "keyword:bank"},
				ProxyRules:  []string{"full:api.example.net", "2001:db8::/32"},
				BlockRules:  []string{"ads.example.com"},
			})
			if err := ValidateConfig(cfg); err != nil {
				t.Fatalf("%s ipv6=%v: %v\n%s", mode, ipv6, err, cfg)
			}
		}
	}
}

func TestBuildConfigNeverOpensPortsByDefault(t *testing.T) {
	p := realityProfile(t)
	cfg := buildOpts(t, BuildOptions{Outbounds: p.Outbounds, Tun: true})
	var c map[string]any
	json.Unmarshal([]byte(cfg), &c)
	for _, ib := range c["inbounds"].([]any) {
		if proto := ib.(map[string]any)["protocol"]; proto != "tun" {
			t.Fatalf("unexpected inbound %v: the app must not listen on any port", proto)
		}
	}
	if strings.Contains(cfg, `"api"`) {
		t.Fatal("API/commander must not be exposed")
	}
}

func TestBuildConfigRejectsBadInput(t *testing.T) {
	if _, err := BuildConfig(`{"outbounds":[],"tun":true}`); err == nil {
		t.Error("expected error for empty outbounds")
	}
	if _, err := BuildConfig(`{"outbounds":[{"protocol":"freedom"},{"tag":"direct","protocol":"freedom"}],"tun":true}`); err == nil {
		t.Error("expected error for reserved tag")
	}
	if _, err := BuildConfig(`{"outbounds":[{"protocol":"freedom"}],"mode":"weird","tun":true}`); err == nil {
		t.Error("expected error for unknown mode")
	}
	if _, err := BuildConfig(`{"outbounds":[{"protocol":"freedom"}]}`); err == nil {
		t.Error("expected error without inbounds")
	}
}

// route asks the real Xray router which outbound a connection would use.
func route(t *testing.T, inst *core.Instance, inbound, domain, ip string, port uint16, network xnet.Network) string {
	t.Helper()
	r := inst.GetFeature(routing.RouterType()).(routing.Router)
	ctx := session.ContextWithInbound(context.Background(), &session.Inbound{Tag: inbound})
	ob := &session.Outbound{}
	if ip != "" {
		ob.Target = xnet.Destination{Network: network, Address: xnet.ParseAddress(ip), Port: xnet.Port(port)}
	} else {
		ob.Target = xnet.Destination{Network: network, Address: xnet.DomainAddress(domain), Port: xnet.Port(port)}
	}
	if domain != "" && ip != "" {
		ob.RouteTarget = xnet.Destination{Network: network, Address: xnet.DomainAddress(domain), Port: xnet.Port(port)}
	}
	ctx = session.ContextWithOutbounds(ctx, []*session.Outbound{ob})
	res, err := r.PickRoute(routingsession.AsRoutingContext(ctx))
	if err != nil {
		t.Fatalf("PickRoute(%s %s): %v", domain, ip, err)
	}
	return res.GetOutboundTag()
}

func TestRoutingDecisions(t *testing.T) {
	useTrimmedGeo(t)
	p := realityProfile(t)
	newInst := func(o BuildOptions) *core.Instance {
		o.Outbounds = p.Outbounds
		o.Tun = true
		inst, err := newInstance(buildOpts(t, o))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { inst.Close() })
		return inst
	}
	tcp, udp := xnet.Network_TCP, xnet.Network_UDP

	type c struct {
		domain, ip string
		port       uint16
		net        xnet.Network
		inbound    string
		want       string
	}
	check := func(name string, inst *core.Instance, cases []c) {
		for _, x := range cases {
			in := x.inbound
			if in == "" {
				in = tunInboundTag
			}
			if got := route(t, inst, in, x.domain, x.ip, x.port, x.net); got != x.want {
				t.Errorf("[%s] %s %s:%d -> %s, want %s", name, x.domain, x.ip, x.port, got, x.want)
			}
		}
	}

	ru := newInst(BuildOptions{Mode: ModeRuDirect, DirectRules: []string{"my-direct.example"}, ProxyRules: []string{"vk.com"}, BlockRules: []string{"yandexadexchange.net"}})
	check("ru_direct", ru, []c{
		{"", TunDNSv4, 53, udp, "", dnsOutTag},                         // DNS from apps -> DNS module
		{"", "77.88.8.8", 53, udp, dnsModuleTag, DirectTag},            // Yandex DNS upstream
		{"", "1.1.1.1", 443, tcp, dnsModuleTag, ProxyTag},              // DoH upstream
		{"yandex.ru", "77.88.55.242", 443, tcp, "", DirectTag},         // .ru
		{"gosuslugi.ru", "213.59.254.7", 443, tcp, "", DirectTag},      // gov
		{"xn--80ajghhoc2aj1c8b.xn--p1ai", "", 443, tcp, "", DirectTag}, // .рф
		{"ozon.com", "", 443, tcp, "", DirectTag},                      // Russian service on .com
		{"", "95.213.1.1", 443, tcp, "", DirectTag},                    // RU IP, no domain (sniff failed)
		{"youtube.com", "142.250.1.1", 443, tcp, "", ProxyTag},         // blocked
		{"www.youtube.com", "", 443, udp, "", ProxyTag},                // QUIC
		{"instagram.com", "", 443, tcp, "", ProxyTag},
		{"example.com", "93.184.215.14", 443, tcp, "", ProxyTag}, // foreign
		{"zona.media", "", 443, tcp, "", ProxyTag},               // blocked media
		{"my-direct.example", "", 443, tcp, "", DirectTag},       // user rule
		{"vk.com", "", 443, tcp, "", ProxyTag},                   // user rule beats RU list
		{"yandexadexchange.net", "", 443, tcp, "", blockTag},     // user block rule
		{"", "192.168.1.1", 80, tcp, "", DirectTag},              // LAN
		{"", "2a00:1450:4001::1", 443, tcp, "", blockTag},        // IPv6 off -> fail fast
	})

	blocked := newInst(BuildOptions{Mode: ModeBlockedOnly})
	check("blocked_only", blocked, []c{
		{"youtube.com", "142.250.1.1", 443, tcp, "", ProxyTag},
		{"chatgpt.com", "", 443, tcp, "", ProxyTag},
		{"example.com", "93.184.215.14", 443, tcp, "", DirectTag},
		{"yandex.ru", "", 443, tcp, "", DirectTag},
		{"", "149.154.167.51", 443, tcp, "", ProxyTag}, // Telegram by IP
		{"", "77.88.8.8", 53, udp, dnsModuleTag, DirectTag},
		{"", "8.8.8.8", 443, tcp, dnsModuleTag, ProxyTag},
	})

	global := newInst(BuildOptions{Mode: ModeGlobal, IPv6: true})
	check("global", global, []c{
		{"yandex.ru", "77.88.55.242", 443, tcp, "", ProxyTag},
		{"", "2a00:1450:4001::1", 443, tcp, "", ProxyTag},
		{"", "192.168.1.1", 80, tcp, "", DirectTag},
		{"", "77.88.8.8", 53, udp, dnsModuleTag, ProxyTag},
	})
}

func TestTunSettings(t *testing.T) {
	var cfg TunConfig
	if err := json.Unmarshal([]byte(TunSettings(false)), &cfg); err != nil {
		t.Fatal(err)
	}
	inRoutes := func(ip string) bool {
		a := netip.MustParseAddr(ip)
		for _, r := range cfg.Routes {
			if netip.MustParsePrefix(r).Contains(a) {
				return true
			}
		}
		return false
	}
	for _, ip := range []string{"8.8.8.8", "1.1.1.1", "77.88.8.8", TunDNSv4, "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0", "223.255.255.255", "2a00:1450::1"} {
		if !inRoutes(ip) {
			t.Errorf("%s must go into the tunnel", ip)
		}
	}
	for _, ip := range []string{"10.1.2.3", "192.168.0.1", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1", "100.64.0.1", "224.0.0.251", "255.255.255.255", "fe80::1", "fd00::1"} {
		if inRoutes(ip) {
			t.Errorf("%s must bypass the tunnel", ip)
		}
	}
	// Routes must not overlap.
	for i, a := range cfg.Routes {
		for _, b := range cfg.Routes[i+1:] {
			if netip.MustParsePrefix(a).Overlaps(netip.MustParsePrefix(b)) {
				t.Errorf("overlap %s %s", a, b)
			}
		}
	}
	t.Logf("%d IPv4+IPv6 routes", len(cfg.Routes))
}

func TestOutboundsAreSanitized(t *testing.T) {
	ob := json.RawMessage(`{"protocol":"vless","streamSettings":{"security":"reality",
		"realitySettings":{"fingerprint":"randomizednoalpn","show":true,"masterKeyLog":"/data/x"},
		"xhttpSettings":{"extra":{"downloadSettings":{"realitySettings":{"masterKeyLog":"/data/y","fingerprint":"ios"}}}}}}`)
	res, err := prepareOutbounds([]json.RawMessage{ob})
	if err != nil {
		t.Fatal(err)
	}
	out := string(mustJSON(res[0]))
	for _, bad := range []string{"masterKeyLog", `"show"`, "randomizednoalpn", `"ios"`} {
		if strings.Contains(out, bad) {
			t.Errorf("sanitized outbound still contains %s: %s", bad, out)
		}
	}
}

func TestBlockedDomainsNeverFallBackToPlainDNS(t *testing.T) {
	cfg, err := buildConfig(&BuildOptions{Outbounds: []json.RawMessage{json.RawMessage(`{"protocol":"vless"}`)}, Mode: ModeRuDirect, Tun: true})
	if err != nil {
		t.Fatal(err)
	}
	servers := cfg["dns"].(map[string]any)["servers"].([]any)
	var blocked []map[string]any
	for _, s := range servers {
		m := s.(map[string]any)
		if d, ok := m["domains"].([]string); ok && len(d) == 1 && d[0] == "geosite:ru-blocked" {
			blocked = append(blocked, m)
		}
	}
	if len(blocked) == 0 || blocked[len(blocked)-1]["finalQuery"] != true {
		t.Fatalf("the last ru-blocked resolver must be final: %v", blocked)
	}
}
