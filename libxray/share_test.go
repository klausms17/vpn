package libxray

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"strings"
	"testing"
)

func TestParseLinks(t *testing.T) {
	k := getKeys(t)
	extra := url.QueryEscape(`{"xPaddingBytes":"100-1000","xmux":{"maxConcurrency":"16-32"}}`)
	vmessJSON := base64.StdEncoding.EncodeToString([]byte(`{"v":"2","ps":"VMess WS","add":"vm.example.com","port":443,"id":"` + k.UUID + `","aid":"0","scy":"auto","net":"ws","type":"none","host":"vm.example.com","path":"/vmws","tls":"tls","sni":"vm.example.com","alpn":"h2,http/1.1","fp":"chrome"}`))
	ssLegacy := base64.StdEncoding.EncodeToString([]byte("aes-256-gcm:secret@198.51.100.7:8388"))

	cases := []struct {
		name  string
		link  string
		check func(t *testing.T, p *Profile, ob map[string]any)
	}{
		{"vless reality vision (3x-ui)",
			"vless://" + k.UUID + "@203.0.113.10:443?type=tcp&security=reality&pbk=" + k.RealityPub + "&fp=chrome&sni=www.google.com&sid=6ba85179e30d4fc2&spx=%2F&flow=xtls-rprx-vision#%F0%9F%87%B3%F0%9F%87%B1%20NL%20Reality",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.Name, "🇳🇱 NL Reality")
				expect(t, p.Address, "203.0.113.10")
				expect(t, p.Port, 443)
				expect(t, p.Network, "raw")
				expect(t, p.Security, "reality")
				expect(t, dig(ob, "settings", "vnext", 0, "users", 0, "flow"), "xtls-rprx-vision")
				expect(t, dig(ob, "settings", "vnext", 0, "users", 0, "encryption"), "none")
				expect(t, dig(ob, "streamSettings", "realitySettings", "password"), k.RealityPub)
				expect(t, dig(ob, "streamSettings", "realitySettings", "shortId"), "6ba85179e30d4fc2")
				expect(t, dig(ob, "streamSettings", "realitySettings", "serverName"), "www.google.com")
				expect(t, dig(ob, "tag"), "proxy")
			}},
		{"vless reality outdated fingerprint is upgraded",
			"vless://" + k.UUID + "@203.0.113.10:443?type=tcp&security=reality&pbk=" + k.RealityPub + "&fp=ios&sni=a.com#x",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "streamSettings", "realitySettings", "fingerprint"), "chrome")
			}},
		{"vless reality xhttp extra + pqv",
			"vless://" + k.UUID + "@srv.example.net:8443?encryption=none&security=reality&sni=ya.ru&fp=firefox&pbk=" + k.RealityPub + "&sid=ab&pqv=" + k.MldsaVerify + "&type=xhttp&path=%2Fxh&mode=stream-one&extra=" + extra + "#xhttp",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.Network, "xhttp")
				expect(t, dig(ob, "streamSettings", "xhttpSettings", "path"), "/xh")
				expect(t, dig(ob, "streamSettings", "xhttpSettings", "mode"), "stream-one")
				expect(t, dig(ob, "streamSettings", "xhttpSettings", "extra", "xPaddingBytes"), "100-1000")
				expect(t, dig(ob, "streamSettings", "realitySettings", "fingerprint"), "firefox")
				expect(t, dig(ob, "streamSettings", "realitySettings", "mldsa65Verify"), k.MldsaVerify)
			}},
		{"vless tls ws with early data",
			"vless://" + k.UUID + "@cdn.example.com:443?encryption=none&security=tls&sni=cdn.example.com&alpn=h2%2Chttp%2F1.1&fp=chrome&type=ws&host=cdn.example.com&path=%2Fws%3Fed%3D2048#ws",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "streamSettings", "wsSettings", "path"), "/ws?ed=2048")
				expect(t, dig(ob, "streamSettings", "wsSettings", "host"), "cdn.example.com")
				expect(t, dig(ob, "streamSettings", "tlsSettings", "alpn", 1), "http/1.1")
			}},
		{"vless post-quantum encryption",
			"vless://" + k.UUID + "@pq.example.com:443?encryption=" + k.VlessEncrypt + "&security=none&type=xhttp&path=%2Fpq#pq",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "settings", "vnext", 0, "users", 0, "encryption"), k.VlessEncrypt)
				expect(t, p.Security, "none")
			}},
		{"vless grpc multi",
			"vless://" + k.UUID + "@g.example.com:443?security=tls&type=grpc&serviceName=svc&mode=multi&authority=g.example.com#g",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "streamSettings", "grpcSettings", "serviceName"), "svc")
				expect(t, dig(ob, "streamSettings", "grpcSettings", "multiMode"), true)
			}},
		{"vless httpupgrade",
			"vless://" + k.UUID + "@h.example.com:443?security=tls&type=httpupgrade&path=%2Fup&host=h.example.com#hu",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "streamSettings", "httpupgradeSettings", "path"), "/up")
			}},
		{"vless raw http header",
			"vless://" + k.UUID + "@10.0.0.5:80?type=tcp&headerType=http&host=a.com,b.com&path=%2Fx#h",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "streamSettings", "rawSettings", "header", "request", "headers", "Host", 1), "b.com")
			}},
		{"vless ipv6 host, no name",
			"vless://" + k.UUID + "@[2001:db8::1]:443?security=reality&pbk=" + k.RealityPub + "&sni=example.com&type=tcp",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.Address, "2001:db8::1")
				expect(t, p.Name, "[2001:db8::1]:443")
			}},
		{"vless allowInsecure asks for pinning",
			"vless://" + k.UUID + "@self.example.com:443?security=tls&allowInsecure=1&type=tcp#self",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.NeedsCertPin, true)
				expect(t, p.CertPinSNI, "self.example.com")
				if _, has := dig(ob, "streamSettings", "tlsSettings").(map[string]any)["allowInsecure"]; has {
					t.Error("allowInsecure must never be passed to Xray")
				}
			}},
		{"vmess base64",
			"vmess://" + vmessJSON,
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.Name, "VMess WS")
				expect(t, p.Port, 443)
				expect(t, p.Security, "tls")
				expect(t, dig(ob, "streamSettings", "wsSettings", "path"), "/vmws")
				expect(t, dig(ob, "settings", "vnext", 0, "users", 0, "security"), "auto")
			}},
		{"trojan",
			"trojan://" + url.PathEscape(k.TrojanPassword) + "@tr.example.com:443?sni=tr.example.com&type=tcp#Trojan",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.Security, "tls")
				expect(t, dig(ob, "settings", "servers", 0, "password"), k.TrojanPassword)
			}},
		{"shadowsocks sip002 base64",
			"ss://" + base64.RawURLEncoding.EncodeToString([]byte("chacha20-ietf-poly1305:pa:ss")) + "@198.51.100.2:8388#SS",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "settings", "servers", 0, "method"), "chacha20-ietf-poly1305")
				expect(t, dig(ob, "settings", "servers", 0, "password"), "pa:ss")
			}},
		{"shadowsocks 2022 plain",
			"ss://2022-blake3-aes-128-gcm:" + url.QueryEscape(k.SS2022Key) + "@198.51.100.3:443#ss22",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, dig(ob, "settings", "servers", 0, "password"), k.SS2022Key)
			}},
		{"shadowsocks legacy",
			"ss://" + ssLegacy + "#legacy",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.Address, "198.51.100.7")
				expect(t, dig(ob, "settings", "servers", 0, "password"), "secret")
			}},
		{"hysteria2 salamander",
			"hysteria2://s3cret@hy.example.com:443/?sni=hy.example.com&obfs=salamander&obfs-password=obfspw&insecure=1#hy2",
			func(t *testing.T, p *Profile, ob map[string]any) {
				expect(t, p.Protocol, "hysteria2")
				expect(t, dig(ob, "streamSettings", "hysteriaSettings", "auth"), "s3cret")
				expect(t, dig(ob, "streamSettings", "finalmask", "udp", 0, "type"), "salamander")
				expect(t, p.NeedsCertPin, true)
				expect(t, p.CertPinQuic, true)
			}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			p := mustParse(t, c.link)
			c.check(t, p, outbound(t, p))
			// Every parsed profile must be accepted by the real Xray core.
			cfg, err := BuildProxyOnlyConfig(outboundsJSON(t, p))
			if err != nil {
				t.Fatal(err)
			}
			if err := ValidateConfig(cfg); err != nil {
				t.Fatalf("xray rejected the config: %v\n%s", err, cfg)
			}
		})
	}
}

func TestParseLinkErrors(t *testing.T) {
	k := getKeys(t)
	cases := map[string]string{
		"vless://" + k.UUID + "@h:443?type=h2&security=tls":                "HTTP/2",
		"vless://" + k.UUID + "@h:443?security=xtls&flow=xtls-rprx-direct": "flow",
		"vless://" + k.UUID + "@h:443?security=reality&sni=a.com":          "pbk",
		"vless://" + k.UUID + "@h:443?type=kcp":                            "mKCP",
		"vless://@h:443":                                                   "UUID",
		"vless://" + k.UUID + "@h?security=none":                           "порт",
		"vless://" + k.UUID + "@h:99999":                                   "порт",
		"happ://crypt3/abcdef":                                             "Happ",
		"https://sub.example.com/abc":                                      "подписк",
		"just some text":                                                   "://",
		"ss://YWVzLTEyOC1nY206cHc@h:1?plugin=obfs-local%3Bobfs%3Dhttp":     "плагин",
		"vmess://not-base64!!!":                                            "VMess",
		"wireguard://x@h:1":                                                "не поддерживается",
	}
	for link, want := range cases {
		_, err := parseLink(link)
		if err == nil {
			t.Errorf("%s: expected error", link)
			continue
		}
		if !strings.Contains(err.Error(), want) {
			t.Errorf("%s: error %q does not mention %q", link, err, want)
		}
	}
}

func TestPinCertificate(t *testing.T) {
	k := getKeys(t)
	p := mustParse(t, "trojan://pw@self.example.com:443?allowInsecure=1#x")
	if !p.NeedsCertPin {
		t.Fatal("expected NeedsCertPin")
	}
	js, _ := json.Marshal(p)
	hash := strings.Repeat("ab", 32)
	pinned, err := PinCertificate(string(js), hash)
	if err != nil {
		t.Fatal(err)
	}
	var p2 Profile
	json.Unmarshal([]byte(pinned), &p2)
	if p2.NeedsCertPin {
		t.Error("NeedsCertPin should be cleared")
	}
	expect(t, dig(outbound(t, &p2), "streamSettings", "tlsSettings", "pinnedPeerCertSha256"), hash)
	cfg, _ := BuildProxyOnlyConfig(outboundsJSON(t, &p2))
	if err := ValidateConfig(cfg); err != nil {
		t.Fatal(err)
	}
	_ = k
}

func TestSubscriptionFormats(t *testing.T) {
	k := getKeys(t)
	l1 := "vless://" + k.UUID + "@a.example.com:443?security=reality&pbk=" + k.RealityPub + "&sni=ya.ru&type=tcp&flow=xtls-rprx-vision#A"
	l2 := "trojan://pw@b.example.com:443#B"
	bad := "vless://" + k.UUID + "@c.example.com:443?type=quic#C"
	plain := l1 + "\r\n\r\n# comment\n" + l2 + "\n" + bad + "\n"

	for name, body := range map[string]string{
		"plain":      plain,
		"base64":     base64.StdEncoding.EncodeToString([]byte(plain)),
		"base64url":  base64.RawURLEncoding.EncodeToString([]byte(plain)),
		"base64wrap": wrap(base64.StdEncoding.EncodeToString([]byte(plain)), 76),
	} {
		res, err := parseSubscription([]byte(body))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if len(res.Profiles) != 2 || res.Profiles[0].Name != "A" || res.Profiles[1].Name != "B" {
			t.Errorf("%s: got %d profiles", name, len(res.Profiles))
		}
		if len(res.Errors) != 1 || !strings.Contains(res.Errors[0], "C") {
			t.Errorf("%s: errors %v", name, res.Errors)
		}
	}

	// Xray JSON configs, the way Remnawave/Marzban serve them, including a
	// chained hop that uses a reserved tag.
	jsonSub := `[
	 {"remarks":"🇩🇪 Germany","outbounds":[
	   {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"de.example.com","port":443,"users":[{"id":"` + k.UUID + `","encryption":"none","flow":"xtls-rprx-vision"}]}]},
	    "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"ya.ru","password":"` + k.RealityPub + `","shortId":"","fingerprint":"chrome"}}},
	   {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
	  "routing":{"rules":[]}},
	 {"remarks":"Chain","outbounds":[
	   {"tag":"exit","protocol":"vless","settings":{"vnext":[{"address":"exit.example.com","port":443,"users":[{"id":"` + k.UUID + `","encryption":"none"}]}]},
	    "streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"serverName":"exit.example.com"},"xhttpSettings":{"path":"/x"},"sockopt":{"dialerProxy":"direct"}}},
	   {"tag":"direct","protocol":"vless","settings":{"vnext":[{"address":"ru-relay.example.ru","port":443,"users":[{"id":"` + k.UUID + `","encryption":"none"}]}]},
	    "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"vk.com","password":"` + k.RealityPub + `","fingerprint":"chrome"}}}]}
	]`
	res, err := parseSubscription([]byte(jsonSub))
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Profiles) != 2 {
		t.Fatalf("got %d profiles: %v", len(res.Profiles), res.Errors)
	}
	de := res.Profiles[0]
	expect(t, de.Name, "🇩🇪 Germany")
	expect(t, de.Address, "de.example.com")
	expect(t, de.Security, "reality")
	expect(t, len(de.Outbounds), 1)
	chain := res.Profiles[1]
	expect(t, len(chain.Outbounds), 2)
	root := outbound(t, chain)
	expect(t, dig(root, "tag"), "proxy")
	hop := dig(root, "streamSettings", "sockopt", "dialerProxy").(string)
	var hopOb map[string]any
	json.Unmarshal(chain.Outbounds[1], &hopOb)
	expect(t, hopOb["tag"], hop)
	if hop == "direct" {
		t.Error("reserved tag must be renamed")
	}
	for _, p := range res.Profiles {
		cfg, err := BuildProxyOnlyConfig(outboundsJSON(t, p))
		if err != nil {
			t.Fatal(err)
		}
		if err := ValidateConfig(cfg); err != nil {
			t.Fatalf("%s: %v", p.Name, err)
		}
	}

	if _, err := parseSubscription([]byte(`{"outbounds":[{"type":"vless","tag":"x"}],"route":{}}`)); err == nil || !strings.Contains(err.Error(), "sing-box") {
		t.Errorf("sing-box config should be rejected clearly, got %v", err)
	}
	if _, err := parseSubscription([]byte("<html>404</html>")); err == nil {
		t.Error("html must be rejected")
	}
	if _, err := parseSubscription(nil); err == nil {
		t.Error("empty must be rejected")
	}
}

func wrap(s string, n int) string {
	var b strings.Builder
	for len(s) > n {
		b.WriteString(s[:n] + "\n")
		s = s[n:]
	}
	b.WriteString(s)
	return b.String()
}

func expect(t *testing.T, got, want any) {
	t.Helper()
	// Normalize numbers decoded from JSON.
	if f, ok := got.(float64); ok {
		if i, ok := want.(int); ok {
			got = int(f)
			_ = i
		}
	}
	if got != want {
		t.Errorf("got %#v, want %#v", got, want)
	}
}

func TestRealityFingerprintAlwaysSendsPostQuantumShare(t *testing.T) {
	cases := map[string]string{
		"chrome": "chrome", "firefox": "firefox", "safari": "safari",
		"randomizednoalpn": "chrome", "random": "chrome", "randomized": "chrome",
		"ios": "chrome", "android": "chrome", "edge": "chrome", "360": "chrome", "qq": "chrome", "": "chrome",
	}
	for in, want := range cases {
		if got := realityFingerprint(in); got != want {
			t.Errorf("realityFingerprint(%q) = %q, want %q", in, got, want)
		}
	}
}
