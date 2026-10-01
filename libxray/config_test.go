package libxray

import (
	"context"
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/policy"
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
	if _, err := BuildProxyOnlyConfig(`[null]`); err == nil || !strings.Contains(err.Error(), "not an object") {
		t.Errorf("a null outbound: %v", err)
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
		{"www.youtube.com", "", 443, udp, "", blockTag},                // QUIC: Vision refuses it anyway
		{"", "93.184.215.14", 443, udp, "", blockTag},                  // QUIC, no domain
		{"vk.com", "", 443, udp, "", blockTag},                         // QUIC by a user proxy rule
		{"", "93.184.215.14", 3478, udp, "", ProxyTag},                 // STUN, calls
		{"yandex.ru", "77.88.55.242", 443, udp, "", DirectTag},         // direct QUIC is kept
		{"my-direct.example", "", 443, udp, "", DirectTag},
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
		{"", "149.154.167.51", 443, udp, "", blockTag},
		{"youtube.com", "", 443, udp, "", blockTag},
		{"example.com", "93.184.215.14", 443, udp, "", DirectTag},
		{"", "77.88.8.8", 53, udp, dnsModuleTag, DirectTag},
		{"", "8.8.8.8", 443, tcp, dnsModuleTag, ProxyTag},
	})

	global := newInst(BuildOptions{Mode: ModeGlobal, IPv6: true})
	check("global", global, []c{
		{"yandex.ru", "77.88.55.242", 443, tcp, "", ProxyTag},
		{"", "2a00:1450:4001::1", 443, tcp, "", ProxyTag},
		{"", "192.168.1.1", 80, tcp, "", DirectTag},
		{"", "77.88.8.8", 53, udp, dnsModuleTag, ProxyTag},
		{"", "77.88.55.242", 443, udp, "", blockTag},
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

// Xray's loader matches keys case-insensitively, so must the sanitizer.
func TestOutboundsAreSanitizedWhateverTheCase(t *testing.T) {
	ob := json.RawMessage(`{"protocol":"hysteria","streamSettings":{"network":"hysteria",
		"finalmask":{"QuicParams":{"congestion":"bbr","Debug":true}},
		"RealitySettings":{"Fingerprint":"ios","Show":true,"MasterKeyLog":"/data/x"}}}`)
	res, err := prepareOutbounds([]json.RawMessage{ob})
	if err != nil {
		t.Fatal(err)
	}
	out := string(mustJSON(res[0]))
	for _, bad := range []string{"Debug", "MasterKeyLog", `"Show"`, `"ios"`} {
		if strings.Contains(out, bad) {
			t.Errorf("sanitized outbound still contains %s: %s", bad, out)
		}
	}
	if !strings.Contains(out, `"congestion":"bbr"`) {
		t.Errorf("sanitizing dropped a harmless key: %s", out)
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

// QUIC is dropped only where it would reach a server with the plain Vision
// flow, which refuses it after a full handshake.
func TestQUICBlockedOnlyForVision(t *testing.T) {
	useTrimmedGeo(t)
	k := getKeys(t)
	links := map[string]string{
		"vision":        "vless://" + k.UUID + "@203.0.113.10:443?type=tcp&security=reality&pbk=" + k.RealityPub + "&sni=www.google.com&sid=ab&flow=xtls-rprx-vision#v",
		"vision-udp443": "vless://" + k.UUID + "@203.0.113.10:443?type=tcp&security=reality&pbk=" + k.RealityPub + "&sni=www.google.com&sid=ab&flow=xtls-rprx-vision-udp443#u",
		"xhttp":         "vless://" + k.UUID + "@203.0.113.10:443?type=xhttp&path=%2Fxh&security=reality&pbk=" + k.RealityPub + "&sni=www.google.com&sid=ab#x",
		"trojan":        "trojan://secret@203.0.113.10:443?security=tls&sni=t.example.com#t",
	}
	for name, link := range links {
		want := ProxyTag
		if name == "vision" {
			want = blockTag
		}
		for _, mode := range []string{ModeRuDirect, ModeGlobal} {
			inst, err := newInstance(buildOpts(t, BuildOptions{Outbounds: mustParse(t, link).Outbounds, Mode: mode, Tun: true}))
			if err != nil {
				t.Fatal(err)
			}
			if got := route(t, inst, tunInboundTag, "www.youtube.com", "", 443, xnet.Network_UDP); got != want {
				t.Errorf("%s %s: QUIC -> %s, want %s", name, mode, got, want)
			}
			if got := route(t, inst, tunInboundTag, "www.youtube.com", "", 443, xnet.Network_TCP); got != ProxyTag {
				t.Errorf("%s %s: TCP -> %s, want proxy", name, mode, got)
			}
			inst.Close()
		}
	}

	// JSON subscriptions may use the flat form and any key case.
	for _, ob := range []string{
		`{"protocol":"vless","settings":{"address":"a.example","port":443,"id":"x","flow":"xtls-rprx-vision"}}`,
		`{"Protocol":"VLESS","Settings":{"Vnext":[{"Users":[{"Flow":"xtls-rprx-vision"}]}]}}`,
	} {
		var m map[string]any
		json.Unmarshal([]byte(ob), &m)
		if !visionFlow(m) {
			t.Errorf("Vision not detected in %s", ob)
		}
	}
	var m map[string]any
	json.Unmarshal([]byte(`{"protocol":"trojan","settings":{"flow":"xtls-rprx-vision"}}`), &m)
	if visionFlow(m) {
		t.Error("only VLESS has the Vision flow")
	}
}

// IP rules pasted from a router page must never make Xray refuse the
// config: every connect would then fail until the rule is removed.
func TestOddIPRulesAreConvertedOrDropped(t *testing.T) {
	cases := map[string]string{
		"fe80::1%wlan0":       "", // zone
		"fe80::1%wlan0/64":    "", // ParseAddr takes "wlan0/64" as the zone
		"::ffff:1.2.3.4":      "1.2.3.4",
		"::ffff:1.2.3.4/128":  "1.2.3.4/32",
		"::ffff:1.2.3.77/120": "1.2.3.0/24",
		"::ffff:1.2.3.4/64":   "", // wider than the mapped range
		"2001:db8::1/32":      "2001:db8::/32",
		"10.1.2.3/8":          "10.0.0.0/8",
	}
	for in, want := range cases {
		_, ips := splitUserRules([]string{in})
		got := strings.Join(ips, ",")
		if got != want {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}

	useTrimmedGeo(t)
	var all []string
	for in := range cases {
		all = append(all, in)
	}
	cfg := buildOpts(t, BuildOptions{Outbounds: realityProfile(t).Outbounds, Tun: true, DirectRules: all, ProxyRules: all, BlockRules: all})
	if err := ValidateConfig(cfg); err != nil {
		t.Fatalf("config refused: %v", err)
	}
}

func TestPolicyLevels(t *testing.T) {
	useTrimmedGeo(t)
	// A subscription's own level (1 is the DNS level) must not decide how
	// long proxied connections live.
	ob := json.RawMessage(`{"protocol":"vless","settings":{"vnext":[{"address":"203.0.113.10","port":443,
		"users":[{"id":"b831381d-6324-4d53-ad4f-8cda48b30811","encryption":"none","level":1}]}]},
		"streamSettings":{"sockopt":{"customSockopt":[{"level":"6","opt":"13","value":"1"}]}}}`)
	hop := json.RawMessage(`{"tag":"frag","protocol":"freedom","settings":{"UserLevel":8,"fragment":{"packets":"tlshello"}}}`)
	obs, err := prepareOutbounds([]json.RawMessage{ob, hop})
	if err != nil {
		t.Fatal(err)
	}
	if lv := dig(obs[0], "settings", "vnext", 0, "users", 0, "level"); lv != levelProxy {
		t.Errorf("user level %v, want %d", lv, levelProxy)
	}
	if lv := dig(obs[1], "settings", "UserLevel"); lv != levelProxy {
		t.Errorf("hop level %v, want %d", lv, levelProxy)
	}
	if lv := dig(obs[0], "streamSettings", "sockopt", "customSockopt", 0, "level"); lv != "6" {
		t.Errorf("a socket option's level must stay: %v", lv)
	}

	cfg := buildOpts(t, BuildOptions{Outbounds: realityProfile(t).Outbounds, Tun: true})
	inst, err := newInstance(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer inst.Close()
	pm := inst.GetFeature(policy.ManagerType()).(policy.Manager)
	for level, want := range map[uint32]time.Duration{levelProxy: 15 * time.Minute, levelDNS: 10 * time.Second, levelDirect: 5 * time.Minute} {
		p := pm.ForLevel(level)
		if p.Timeouts.ConnectionIdle != want {
			t.Errorf("level %d: idle %v, want %v", level, p.Timeouts.ConnectionIdle, want)
		}
		// Only the idle time changes; the 60 s handshake hides REALITY.
		if p.Timeouts.Handshake != 60*time.Second {
			t.Errorf("level %d: handshake %v", level, p.Timeouts.Handshake)
		}
	}
	var c map[string]any
	json.Unmarshal([]byte(cfg), &c)
	for _, x := range c["outbounds"].([]any) {
		ob := x.(map[string]any)
		want := map[string]any{DirectTag: float64(levelDirect), dnsOutTag: float64(levelDNS)}[ob["tag"].(string)]
		if want != nil && dig(ob, "settings", "userLevel") != want {
			t.Errorf("%s runs at level %v, want %v", ob["tag"], dig(ob, "settings", "userLevel"), want)
		}
	}
}

func TestDNSServesStaleNames(t *testing.T) {
	for _, mode := range []string{ModeRuDirect, ModeBlockedOnly, ModeGlobal} {
		d := buildDNS(&BuildOptions{Mode: mode}, nil)
		if d["serveStale"] != true || d["serveExpiredTTL"] != 3600 {
			t.Errorf("%s: stale answers off: %v", mode, d)
		}
	}
}
