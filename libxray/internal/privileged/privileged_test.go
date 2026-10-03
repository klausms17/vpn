package privileged_test

import (
	"encoding/json"
	"net/url"
	"strings"
	"testing"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/internal/privileged"
)

const uuid = "11111111-2222-3333-4444-555555555555"

func outbounds(t *testing.T, link string) []json.RawMessage {
	t.Helper()
	out, err := libxray.ParseLink(link)
	if err != nil {
		t.Fatalf("%s: %v", link, err)
	}
	var p libxray.Profile
	if err := json.Unmarshal([]byte(out), &p); err != nil {
		t.Fatal(err)
	}
	return p.Outbounds
}

func TestWhatShareLinksMakeIsAllowed(t *testing.T) {
	extra := url.QueryEscape(`{"xPaddingBytes":"100-1000","xmux":{"maxConcurrency":"16-32"},"downloadSettings":{"address":"dl.example.com","port":443,"network":"xhttp","security":"tls","tlsSettings":{"serverName":"dl.example.com"},"xhttpSettings":{"path":"/dl"}}}`)
	fm := url.QueryEscape(`{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200"}}]}`)
	for _, link := range []string{
		"vless://" + uuid + "@vpn.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome&flow=xtls-rprx-vision#DE",
		"vless://" + uuid + "@vpn.example.com:443?type=ws&security=tls&path=%2Fws&host=cdn.example.com&heartbeatPeriod=30#WS",
		"vless://" + uuid + "@vpn.example.com:443?type=xhttp&security=tls&path=%2Fx&mode=packet-up&extra=" + extra + "#XHTTP",
		"vless://" + uuid + "@vpn.example.com:443?type=grpc&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&serviceName=s&mode=multi&fm=" + fm + "#gRPC",
		"vless://" + uuid + "@vpn.example.com:443?type=httpupgrade&security=tls&path=%2Fu&pcs=" + strings.Repeat("ab", 32) + "#HU",
		"trojan://secret@vpn.example.com:443?type=tcp&headerType=http&path=%2F&host=a.example.com#Trojan",
		"vmess://" + uuid + "@vpn.example.com:443?type=ws&security=tls&path=%2Fv#VMess",
		"ss://YWVzLTI1Ni1nY206c2VjcmV0@vpn.example.com:8388#SS",
		"hysteria2://auth@vpn.example.com:443?sni=vpn.example.com&obfs=salamander&obfs-password=pw#Hy2",
	} {
		if err := privileged.Check(outbounds(t, link)); err != nil {
			t.Errorf("%s: %v", link, err)
		}
	}
}

func TestAnythingElseIsRefused(t *testing.T) {
	vless := func(stream string) string {
		return `{"protocol":"vless","settings":{"vnext":[{"address":"a.example","port":443,"users":[{"id":"` + uuid + `","encryption":"none"}]}]},"streamSettings":` + stream + `}`
	}
	for name, ob := range map[string]string{
		"xdrive writes files":           vless(`{"network":"xdrive","xdriveSettings":{"service":"local","remoteFolder":"C:\\Windows\\System32"}}`),
		"in any case":                   vless(`{"network":"XDrive"}`),
		"as a second spelling":          vless(`{"network":"raw","Network":"xdrive"}`),
		"through the method alias":      vless(`{"method":"xdrive"}`),
		"inside xhttp's extra":          vless(`{"network":"xhttp","xhttpSettings":{"extra":{"downloadSettings":{"network":"xdrive"}}}}`),
		"in a download's own extra":     vless(`{"network":"xhttp","xhttpSettings":{"downloadSettings":{"network":"xhttp","xhttpSettings":{"extra":{}}}}}`),
		"a TLS key log":                 vless(`{"security":"tls","tlsSettings":{"MasterKeyLog":"C:\\keys.txt"}}`),
		"certificate files":             vless(`{"security":"tls","tlsSettings":{"certificates":[{"certificateFile":"a","keyFile":"b"}]}}`),
		"skipping the certificate":      vless(`{"security":"tls","tlsSettings":{"allowInsecure":true}}`),
		"a REALITY key log":             vless(`{"security":"reality","realitySettings":{"masterKeyLog":"x"}}`),
		"REALITY's debug output":        vless(`{"security":"reality","realitySettings":{"show":true}}`),
		"an unknown security":           vless(`{"security":"xtls"}`),
		"mKCP":                          vless(`{"network":"kcp"}`),
		"MASQUE":                        vless(`{"network":"masque","masqueSettings":{}}`),
		"binding to an interface":       vless(`{"sockopt":{"interface":"Kirov VPN"}}`),
		"a dialer proxy":                vless(`{"sockopt":{"dialerProxy":"direct"}}`),
		"raw socket options":            vless(`{"sockopt":{"customSockopt":[{"level":"6","opt":"1","value":"1"}]}}`),
		"UDP port hopping":              vless(`{"finalmask":{"udp":[{"type":"udphop","settings":{}}]}}`),
		"ICMP":                          vless(`{"finalmask":{"udp":[{"type":"xicmp"}]}}`),
		"QUIC debug":                    vless(`{"finalmask":{"quicParams":{"debug":true}}}`),
		"Hysteria's masquerade":         vless(`{"network":"hysteria","hysteriaSettings":{"masquerade":{"type":"file","dir":"C:\\"}}}`),
		"a key with a Kelvin sign":      vless(`{"networ\u212a":"raw"}`),
		"VLESS reverse":                 `{"protocol":"vless","settings":{"address":"a.example","port":443,"id":"` + uuid + `","reverse":{"tag":"r"}}}`,
		"reverse in a user":             `{"protocol":"vless","settings":{"vnext":[{"address":"a","port":1,"users":[{"id":"` + uuid + `","reverse":{"tag":"r"}}]}]}}`,
		"freedom sends past the server": `{"protocol":"freedom"}`,
		"a DNS outbound":                `{"protocol":"dns"}`,
		"loopback":                      `{"protocol":"loopback","settings":{"inboundTag":"tun"}}`,
		"no protocol":                   `{"settings":{}}`,
		"proxySettings":                 `{"protocol":"trojan","settings":{"servers":[]},"proxySettings":{"tag":"direct"}}`,
		"sendThrough":                   `{"protocol":"trojan","settings":{"servers":[]},"sendThrough":"127.0.0.1"}`,
		"not an object":                 `"vless"`,
	} {
		if err := privileged.Check([]json.RawMessage{json.RawMessage(ob)}); err == nil {
			t.Errorf("%s: allowed", name)
		} else if !strings.HasPrefix(err.Error(), "ключ просит у ядра") {
			t.Errorf("%s: %v", name, err)
		}
	}
	if err := privileged.Check(nil); err == nil {
		t.Error("no outbounds: allowed")
	}
	if err := privileged.Check([]json.RawMessage{json.RawMessage(`{`)}); err == nil {
		t.Error("broken JSON: allowed")
	}
}

func TestTheErrorNamesTheSameSettingEveryTime(t *testing.T) {
	ob := []json.RawMessage{json.RawMessage(`{"protocol":"vless","settings":{},"streamSettings":{"xdriveSettings":{},"network":"xdrive","finalmask":{"udp":[{"type":"udphop"}]}}}`)}
	want := privileged.Check(ob).Error()
	for range 20 {
		if got := privileged.Check(ob).Error(); got != want {
			t.Fatalf("%q, then %q", want, got)
		}
	}
	if !strings.HasSuffix(want, "(outbounds[0].streamSettings.finalmask.udp[0].type: udphop)") {
		t.Errorf("error %q", want)
	}
}

func TestEveryOutboundIsChecked(t *testing.T) {
	good := outbounds(t, "trojan://secret@vpn.example.com:443#T")
	if err := privileged.Check(append(good, json.RawMessage(`{"protocol":"freedom","tag":"fragment"}`))); err == nil {
		t.Error("a second outbound was not checked")
	}
}
