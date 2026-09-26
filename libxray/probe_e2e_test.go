package libxray

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// probeServers starts, on localhost, a VLESS REALITY server (A) and a Trojan
// TLS server (B) and returns link builders for them.
type probeServers struct {
	realityLink func(port int) string
	trojanLink  func(port int) string
	portA       int
	portB       int
}

func startProbeServers(t *testing.T) probeServers {
	t.Helper()
	k := getKeys(t)
	realityTarget := bigCertTLSServer(t)
	realityDest := strings.TrimPrefix(realityTarget.URL, "https://")
	cert := makeCert(t, t.TempDir(), "e2e.example.com")

	s := probeServers{portA: freePort(t), portB: freePort(t)}
	startServer(t, fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q,"flow":"xtls-rprx-vision"}],"decryption":"none"},
	  "streamSettings":{"network":"raw","security":"reality","realitySettings":{"target":%q,"serverNames":["example.com"],"privateKey":%q,"shortIds":["ab12"]}}}`,
		s.portA, k.UUID, realityDest, k.RealityPriv))
	startServer(t, fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"trojan","settings":{"clients":[{"password":%q}]},
	  "streamSettings":{"network":"raw","security":"tls","tlsSettings":{"certificates":[{"certificateFile":%q,"keyFile":%q}]}}}`,
		s.portB, k.TrojanPassword, cert.certFile, cert.keyFile))
	s.realityLink = func(port int) string {
		return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=tcp&security=reality&pbk=%s&fp=chrome&sni=example.com&sid=ab12&flow=xtls-rprx-vision#A", k.UUID, port, k.RealityPub)
	}
	s.trojanLink = func(port int) string {
		return fmt.Sprintf("trojan://%s@127.0.0.1:%d?security=tls&sni=e2e.example.com&pcs=%s#B", uriComponent(k.TrojanPassword), port, cert.sha256)
	}
	return s
}

// chainJSON is a profile that reaches B through A: B's outbound dials via
// sockopt.dialerProxy, the way JSON subscriptions chain servers.
func (s probeServers) chainJSON(t *testing.T, hopPort int) string {
	root := outbound(t, mustParse(t, s.trojanLink(s.portB)))
	root["streamSettings"].(map[string]any)["sockopt"] = map[string]any{"dialerProxy": "hop"}
	hop := outbound(t, mustParse(t, s.realityLink(hopPort)))
	hop["tag"] = "hop"
	b, _ := json.Marshal([]any{root, hop})
	return string(b)
}

// logGrows reports whether the file grows within d after action runs, once
// earlier writes (Xray logs asynchronously) have settled.
func logGrows(t *testing.T, path string, d time.Duration, action func()) bool {
	t.Helper()
	size := func() int64 {
		fi, err := os.Stat(path)
		if err != nil {
			return 0
		}
		return fi.Size()
	}
	before := size()
	for stable := time.Now(); time.Since(stable) < 300*time.Millisecond; {
		time.Sleep(20 * time.Millisecond)
		if s := size(); s != before {
			before, stable = s, time.Now()
		}
	}
	action()
	for deadline := time.Now().Add(d); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		if size() > before {
			return true
		}
	}
	return false
}

func TestProbeAndTunnelFetch(t *testing.T) {
	useTrimmedGeo(t)
	var sawHeaders atomic.Value
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/generate_204" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		sawHeaders.Store(r.Header.Clone())
		w.Header().Set("Profile-Title", "base64:0JTRgNGD0LfRjNGP")
		w.Header().Set("X-Hwid-Active", "true")
		fmt.Fprint(w, "links")
	}))
	defer target.Close()
	testURL := target.URL + "/generate_204"

	// Servers first: every instance takes over Xray's process-wide state
	// when it is created, the servers must not do that after the tunnel.
	s := startProbeServers(t)
	dead := freePort(t)
	a := mustParse(t, s.realityLink(s.portA))

	// The tunnel runs a chained server (B via A): the hop is found through
	// process-wide state as well.
	var chain []json.RawMessage
	json.Unmarshal([]byte(s.chainJSON(t, s.portA)), &chain)
	logFile := filepath.Join(t.TempDir(), "xray.log")
	ctrl := NewController()
	cfg := buildOpts(t, BuildOptions{Outbounds: chain, Mode: ModeGlobal, SocksPort: freePort(t), LogLevel: "info", LogFile: logFile})
	if err := ctrl.Start(cfg, 0); err != nil {
		t.Fatal(err)
	}
	defer ctrl.Stop()
	var tunnelErr error
	measure := func() { _, tunnelErr = ctrl.MeasureDelay(testURL, 5000) }
	if !logGrows(t, logFile, 3*time.Second, measure) || tunnelErr != nil {
		t.Fatalf("the tunnel does not work or log: %v", tunnelErr)
	}

	// The old way (a temporary instance) silences the tunnel's log and
	// breaks its chain; this proves the checks below see the difference.
	proxyOnly, _ := BuildProxyOnlyConfig(outboundsJSON(t, a))
	if _, err := MeasureOutboundDelay(proxyOnly, testURL, 5000); err != nil {
		t.Fatal(err)
	}
	if logGrows(t, logFile, time.Second, measure) || tunnelErr == nil {
		t.Fatalf("expected a temporary instance to take the log and the hop over (tunnel error: %v)", tunnelErr)
	}

	t.Run("ProbeOutbounds", func(t *testing.T) {
		// Passes the per-outbound checks, yet the core refuses it (an ML-KEM
		// key out of range): the batch must survive it too.
		badKey := mustParse(t, strings.Replace(s.realityLink(s.portA), "#", "&encryption=mlkem768x25519plus.native.0rtt."+b64url(bytes.Repeat([]byte{0xff}, 1184))+"#", 1))
		badCfg, _ := BuildProxyOnlyConfig(outboundsJSON(t, badKey))
		if _, _, err := probeCandidate(json.RawMessage(outboundsJSON(t, badKey)), "x"); err != nil || ValidateConfig(badCfg) == nil {
			t.Fatalf("the test needs a candidate that only core.New refuses: %v", err)
		}
		candidates := "[" + strings.Join([]string{
			outboundsJSON(t, a), // 0 live
			outboundsJSON(t, mustParse(t, s.realityLink(dead))),   // 1 nothing listens
			s.chainJSON(t, s.portA),                               // 2 B via A
			s.chainJSON(t, dead),                                  // 3 B via a dead hop: the hop is really used
			`[{"protocol":"vless","settings":{"vnext":"bogus"}}]`, // 4 invalid
			`{"not":"an array"}`,                                  // 5 invalid
			outboundsJSON(t, mustParse(t, s.trojanLink(s.portB))), // 6 B directly
			outboundsJSON(t, badKey),                              // 7 refused by the core
		}, ",") + "]"
		start := time.Now()
		out, err := ctrl.ProbeOutbounds(candidates, testURL, 5000, 4)
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("results %s in %v", out, time.Since(start).Round(time.Millisecond))
		var res []int64
		if err := json.Unmarshal([]byte(out), &res); err != nil {
			t.Fatal(err)
		}
		want := []bool{true, false, true, false, false, false, true, false}
		if len(res) != len(want) {
			t.Fatalf("got %d results for %d candidates", len(res), len(want))
		}
		for i, ok := range want {
			if (res[i] >= 0) != ok {
				t.Errorf("candidate %d: got %d, want working=%v", i, res[i], ok)
			}
		}
	})

	t.Run("tunnel restored", func(t *testing.T) {
		grew := logGrows(t, logFile, 3*time.Second, measure)
		if tunnelErr != nil {
			t.Fatalf("the chained tunnel is broken after a probe: %v", tunnelErr)
		}
		if !grew {
			t.Fatal("xray.log stays silent after a probe: the tunnel's logger was not restored")
		}
	})

	t.Run("FetchThroughTunnel", func(t *testing.T) {
		ctrl.QueryTraffic() // reset
		res, err := ctrl.FetchThroughTunnel(target.URL+"/sub/token", "KlausVPN/1.0 (Android)", `{"X-Hwid":"0123456789abcdef","X-Device-Os":"Android"}`, 5000)
		if err != nil {
			t.Fatal(err)
		}
		expect(t, string(res.Body), "links")
		expect(t, res.ProfileTitle, "Друзья")
		expect(t, res.HwidActive, true)
		h, _ := sawHeaders.Load().(http.Header)
		expect(t, h.Get("X-Hwid"), "0123456789abcdef")
		expect(t, h.Get("X-Device-Os"), "Android")
		expect(t, h.Get("User-Agent"), "KlausVPN/1.0 (Android)")
		if tr := ctrl.QueryTraffic(); tr.ProxyDown == 0 {
			t.Errorf("the fetch did not go through the proxy outbound: %+v", tr)
		}
		if _, err := ctrl.FetchThroughTunnel(target.URL, "", `{"bad name":"x"}`, 5000); err == nil {
			t.Error("bad headers must be refused")
		}
	})

	t.Run("stopped controller", func(t *testing.T) {
		if err := ctrl.Stop(); err != nil {
			t.Fatal(err)
		}
		if _, err := ctrl.FetchThroughTunnel(target.URL, "", "", 5000); err == nil {
			t.Error("FetchThroughTunnel must fail when the core is not running")
		}
		out, err := ctrl.ProbeOutbounds("["+outboundsJSON(t, a)+","+outboundsJSON(t, mustParse(t, s.realityLink(dead)))+"]", testURL, 5000, 0)
		if err != nil {
			t.Fatal(err)
		}
		var res []int64
		json.Unmarshal([]byte(out), &res)
		if len(res) != 2 || res[0] < 0 || res[1] != -1 {
			t.Errorf("got %s", out)
		}
		if out, err := ctrl.ProbeOutbounds("[]", testURL, 5000, 4); err != nil || out != "[]" {
			t.Errorf("no candidates: %q %v", out, err)
		}
		if _, err := ctrl.ProbeOutbounds("{", testURL, 5000, 4); err == nil {
			t.Error("broken JSON must be an error")
		}
	})
}
