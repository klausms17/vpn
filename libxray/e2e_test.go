package libxray

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	gonet "net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
)

// End-to-end: real Xray servers on localhost, clients built exactly the way
// the app builds them (share link -> ParseLink -> [cert pin] -> BuildConfig
// -> Controller.Start), then real TCP (HTTP) and UDP traffic through the
// proxy outbound.

var portSeq atomic.Int32

var serverLogLevel = "warning"

func freePort(t *testing.T) int {
	for {
		p := 24000 + int(portSeq.Add(1))
		l, err := gonet.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
		if err != nil {
			continue
		}
		l.Close()
		u, err := gonet.ListenPacket("udp", fmt.Sprintf("127.0.0.1:%d", p))
		if err != nil {
			continue
		}
		u.Close()
		return p
	}
}

type testCert struct {
	certFile, keyFile string
	sha256            string
}

func makeCert(t *testing.T, dir, name string) testCert {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: name},
		DNSNames:     []string{name},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	kb, _ := x509.MarshalECPrivateKey(key)
	c := testCert{certFile: filepath.Join(dir, name+".crt"), keyFile: filepath.Join(dir, name+".key")}
	os.WriteFile(c.certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600)
	os.WriteFile(c.keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600)
	sum := sha256.Sum256(der)
	c.sha256 = hex.EncodeToString(sum[:])
	return c
}

func startServer(t *testing.T, inbound string) {
	t.Helper()
	cfg := `{"log":{"loglevel":"` + serverLogLevel + `"},"inbounds":[` + inbound + `],
	  "outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow"}]}}]}`
	inst, err := newInstance(cfg)
	if err != nil {
		t.Fatalf("server config: %v\n%s", err, cfg)
	}
	if err := inst.Start(); err != nil {
		t.Fatalf("server start: %v", err)
	}
	t.Cleanup(func() { inst.Close() })
}

func udpEcho(t *testing.T) int {
	pc, err := gonet.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(append([]byte("echo:"), buf[:n]...), addr)
		}
	}()
	return pc.LocalAddr().(*gonet.UDPAddr).Port
}

func udpRoundTrip(inst *core.Instance, port int) error {
	ctx := session.SetForcedOutboundTagToContext(t0(), ProxyTag)
	conn, err := core.Dial(ctx, inst, xnet.UDPDestination(xnet.LocalHostIP, xnet.Port(port)))
	if err != nil {
		return err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write([]byte("ping")); err != nil {
		return err
	}
	buf := make([]byte, 64)
	n, err := conn.Read(buf)
	if err != nil {
		return err
	}
	if string(buf[:n]) != "echo:ping" {
		return fmt.Errorf("bad echo %q", buf[:n])
	}
	return nil
}

func TestEndToEnd(t *testing.T) {
	useTrimmedGeo(t)
	k := getKeys(t)
	dir := t.TempDir()

	var hits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.URL.Path == "/generate_204" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		w.Header().Set("Subscription-Userinfo", "upload=1; download=2; total=3; expire=4")
		fmt.Fprint(w, "hello-e2e")
	}))
	defer target.Close()
	testURL := target.URL + "/generate_204"
	echoPort := udpEcho(t)

	// REALITY borrows the TLS handshake of a real TLS 1.3 site.
	realityTarget := bigCertTLSServer(t)
	realityDest := strings.TrimPrefix(realityTarget.URL, "https://")

	cert := makeCert(t, dir, "e2e.example.com")
	tlsServer := fmt.Sprintf(`"security":"tls","tlsSettings":{"alpn":["h2","http/1.1"],"certificates":[{"certificateFile":%q,"keyFile":%q}]}`, cert.certFile, cert.keyFile)
	hyTLS := fmt.Sprintf(`"security":"tls","tlsSettings":{"alpn":["h3"],"certificates":[{"certificateFile":%q,"keyFile":%q}]}`, cert.certFile, cert.keyFile)
	reality := func(extra string) string {
		return fmt.Sprintf(`"security":"reality","realitySettings":{"target":%q,"serverNames":["example.com"],"privateKey":%q,"shortIds":["","ab12"]%s}`, realityDest, k.RealityPriv, extra)
	}

	type tc struct {
		name    string
		inbound func(port int) string
		link    func(port int) string
		noUDP   bool
	}
	cases := []tc{
		{"VLESS REALITY Vision",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q,"flow":"xtls-rprx-vision"}],"decryption":"none"},"streamSettings":{"network":"raw",%s}}`, p, k.UUID, reality(""))
			},
			func(p int) string {
				return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=tcp&security=reality&pbk=%s&fp=chrome&sni=example.com&sid=ab12&flow=xtls-rprx-vision#Vision", k.UUID, p, k.RealityPub)
			}, false},
		{"VLESS REALITY with outdated fingerprint (edge)",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q,"flow":"xtls-rprx-vision"}],"decryption":"none"},"streamSettings":{"network":"raw",%s}}`, p, k.UUID, reality(""))
			},
			func(p int) string {
				return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=tcp&security=reality&pbk=%s&fp=edge&sni=example.com&sid=ab12&flow=xtls-rprx-vision#Edge", k.UUID, p, k.RealityPub)
			}, false},
		{"VLESS REALITY XHTTP + ML-DSA",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q}],"decryption":"none"},"streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/xh"},%s}}`, p, k.UUID, reality(fmt.Sprintf(`,"mldsa65Seed":%q`, k.MldsaSeed)))
			},
			func(p int) string {
				return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=xhttp&path=%%2Fxh&mode=auto&security=reality&pbk=%s&pqv=%s&fp=chrome&sni=example.com&sid=#XHTTP", k.UUID, p, k.RealityPub, k.MldsaVerify)
			}, false},
		{"VLESS post-quantum encryption over XHTTP",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q}],"decryption":%q},"streamSettings":{"network":"xhttp","xhttpSettings":{"path":"/pq"}}}`, p, k.UUID, k.VlessDecrypt)
			},
			func(p int) string {
				return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=xhttp&path=%%2Fpq&encryption=%s#PQ", k.UUID, p, k.VlessEncrypt)
			}, false},
		{"VLESS TLS WebSocket (self-signed, pinned)",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q}],"decryption":"none"},"streamSettings":{"network":"ws","wsSettings":{"path":"/ws"},%s}}`, p, k.UUID, tlsServer)
			},
			func(p int) string {
				return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=ws&path=%%2Fws&security=tls&sni=e2e.example.com&allowInsecure=1#WS", k.UUID, p)
			}, false},
		{"VLESS TLS gRPC",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q}],"decryption":"none"},"streamSettings":{"network":"grpc","grpcSettings":{"serviceName":"svc"},%s}}`, p, k.UUID, tlsServer)
			},
			func(p int) string {
				return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=grpc&serviceName=svc&security=tls&sni=e2e.example.com&pcs=%s#gRPC", k.UUID, p, cert.sha256)
			}, false},
		{"VLESS TLS HTTPUpgrade",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q}],"decryption":"none"},"streamSettings":{"network":"httpupgrade","httpupgradeSettings":{"path":"/up"},%s}}`, p, k.UUID, tlsServer)
			},
			func(p int) string {
				return fmt.Sprintf("vless://%s@127.0.0.1:%d?type=httpupgrade&path=%%2Fup&security=tls&sni=e2e.example.com&pcs=%s#HU", k.UUID, p, cert.sha256)
			}, false},
		{"Trojan TLS",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"trojan","settings":{"clients":[{"password":%q}]},"streamSettings":{"network":"raw",%s}}`, p, k.TrojanPassword, tlsServer)
			},
			func(p int) string {
				return fmt.Sprintf("trojan://%s@127.0.0.1:%d?security=tls&sni=e2e.example.com&allowInsecure=1#Trojan", url.PathEscape(k.TrojanPassword), p)
			}, false},
		{"VMess WebSocket",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"vmess","settings":{"clients":[{"id":%q}]},"streamSettings":{"network":"ws","wsSettings":{"path":"/vm"}}}`, p, k.UUID)
			},
			func(p int) string {
				js, _ := json.Marshal(map[string]any{"v": "2", "ps": "VMess", "add": "127.0.0.1", "port": fmt.Sprint(p), "id": k.UUID, "aid": "0", "net": "ws", "path": "/vm", "tls": ""})
				return "vmess://" + b64std(js)
			}, false},
		{"Shadowsocks 2022",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"shadowsocks","settings":{"method":"2022-blake3-aes-128-gcm","password":%q,"network":"tcp,udp"}}`, p, k.SS2022Key)
			},
			func(p int) string {
				return fmt.Sprintf("ss://2022-blake3-aes-128-gcm:%s@127.0.0.1:%d#SS2022", url.QueryEscape(k.SS2022Key), p)
			}, false},
		{"Shadowsocks AEAD",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"shadowsocks","settings":{"method":"aes-256-gcm","password":"old-secret","network":"tcp,udp"}}`, p)
			},
			func(p int) string {
				return fmt.Sprintf("ss://%s@127.0.0.1:%d#SS", b64std([]byte("aes-256-gcm:old-secret")), p)
			}, false},
		{"Hysteria2 + Salamander",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"hysteria","settings":{"version":2,"clients":[{"auth":"hy-secret"}]},"streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2},"finalmask":{"udp":[{"type":"salamander","settings":{"password":"obfs-pw"}}]},%s}}`, p, hyTLS)
			},
			func(p int) string {
				return fmt.Sprintf("hysteria2://hy-secret@127.0.0.1:%d/?sni=e2e.example.com&obfs=salamander&obfs-password=obfs-pw&pinSHA256=%s#HY2", p, cert.sha256)
			}, false},
		{"Hysteria2 self-signed (pinned over QUIC)",
			func(p int) string {
				return fmt.Sprintf(`{"listen":"127.0.0.1","port":%d,"protocol":"hysteria","settings":{"version":2,"clients":[{"auth":"hy-secret"}]},"streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2},%s}}`, p, hyTLS)
			},
			func(p int) string {
				return fmt.Sprintf("hy2://hy-secret@127.0.0.1:%d?sni=e2e.example.com&insecure=1#HY2", p)
			}, false},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			port := freePort(t)
			startServer(t, c.inbound(port))

			// Import, exactly like the app.
			profileJSON, err := ParseLink(c.link(port))
			if err != nil {
				t.Fatal(err)
			}
			var p Profile
			json.Unmarshal([]byte(profileJSON), &p)
			if p.NeedsCertPin {
				hash, err := FetchCertSha256(p.Address, int32(p.Port), p.CertPinSNI, p.CertPinQuic, 5000)
				if err != nil {
					t.Fatalf("fetch cert: %v", err)
				}
				if hash != cert.sha256 {
					t.Fatalf("pinned %s, want %s", hash, cert.sha256)
				}
				if profileJSON, err = PinCertificate(profileJSON, hash); err != nil {
					t.Fatal(err)
				}
				json.Unmarshal([]byte(profileJSON), &p)
			}
			outbounds, _ := json.Marshal(p.Outbounds)

			// 1. Server test while "disconnected" (profile list ping).
			proxyOnly, err := BuildProxyOnlyConfig(string(outbounds))
			if err != nil {
				t.Fatal(err)
			}
			if ms, err := MeasureOutboundDelay(proxyOnly, testURL, 8000); err != nil {
				t.Fatalf("MeasureOutboundDelay: %v", err)
			} else {
				t.Logf("delay %d ms", ms)
			}

			// 2. Full VPN config, as used by the VPN service.
			before := hits.Load()
			cfg := withStats(t, buildOpts(t, BuildOptions{Outbounds: p.Outbounds, Mode: ModeRuDirect, SocksPort: freePort(t)}))
			ctrl := NewController()
			if err := ctrl.Start(cfg, 0); err != nil {
				t.Fatalf("start: %v", err)
			}
			defer ctrl.Stop()
			if _, err := ctrl.MeasureDelay(testURL, 8000); err != nil {
				t.Fatalf("MeasureDelay through running core: %v", err)
			}
			if hits.Load() <= before {
				t.Fatal("request did not reach the target")
			}
			ctrl.mu.Lock()
			inst := ctrl.cur.inst
			ctrl.mu.Unlock()
			if !c.noUDP {
				if err := udpRoundTrip(inst, echoPort); err != nil {
					t.Fatalf("UDP through proxy: %v", err)
				}
			}
			tr := takeTraffic(t, ctrl)
			if tr.proxyUp == 0 || tr.proxyDown == 0 {
				t.Errorf("traffic counters not working: %+v", tr)
			}

			// 3. Subscription download through the proxy.
			res, err := Fetch(target.URL+"/sub", "test", 8000, proxyOnly)
			if err != nil {
				t.Fatalf("Fetch via proxy: %v", err)
			}
			if string(res.Body) != "hello-e2e" || !strings.Contains(res.UserInfo, "total=3") {
				t.Fatalf("bad fetch result %q %q", res.Body, res.UserInfo)
			}
		})
	}

	// A wrong key must fail cleanly (no hang, no crash).
	t.Run("wrong REALITY key fails fast", func(t *testing.T) {
		port := freePort(t)
		startServer(t, cases[0].inbound(port))
		_, otherPub := x25519Pair(t)
		p := mustParse(t, fmt.Sprintf("vless://%s@127.0.0.1:%d?type=tcp&security=reality&pbk=%s&sni=example.com&sid=ab12&flow=xtls-rprx-vision", k.UUID, port, otherPub))
		proxyOnly, _ := BuildProxyOnlyConfig(outboundsJSON(t, p))
		start := time.Now()
		if _, err := MeasureOutboundDelay(proxyOnly, testURL, 5000); err == nil {
			t.Fatal("expected failure with a wrong key")
		}
		if time.Since(start) > 7*time.Second {
			t.Fatal("failure took too long")
		}
	})
}

func b64std(b []byte) string {
	return strings.TrimRight(base64Std.EncodeToString(b), "=")
}

// bigCertTLSServer imitates a real website for REALITY: TLS 1.3 with a
// realistic RSA certificate chain (several KB). REALITY needs a chain that
// large to hide the ML-DSA-65 signature.
func bigCertTLSServer(t *testing.T) *httptest.Server {
	t.Helper()
	caKey, _ := rsa.GenerateKey(rand.Reader, 4096)
	caTmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Test Root CA", Organization: []string{"Example Trust Services"}},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(48 * time.Hour), IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign}
	caDER, _ := x509.CreateCertificate(rand.Reader, caTmpl, caTmpl, &caKey.PublicKey, caKey)
	ca, _ := x509.ParseCertificate(caDER)
	imKey, _ := rsa.GenerateKey(rand.Reader, 4096)
	imTmpl := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "Test Intermediate CA R1", Organization: []string{"Example Trust Services"}},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(48 * time.Hour), IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign}
	imDER, _ := x509.CreateCertificate(rand.Reader, imTmpl, ca, &imKey.PublicKey, caKey)
	im, _ := x509.ParseCertificate(imDER)
	leafKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	leafTmpl := &x509.Certificate{SerialNumber: big.NewInt(3), Subject: pkix.Name{CommonName: "example.com"},
		DNSNames:  []string{"example.com", "www.example.com", "cdn.example.com", "static.example.com", "api.example.com", "img.example.com"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	leafDER, _ := x509.CreateCertificate(rand.Reader, leafTmpl, im, &leafKey.PublicKey, imKey)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{leafDER, imDER, caDER}, PrivateKey: leafKey}}}
	srv.EnableHTTP2 = true
	srv.StartTLS()
	t.Cleanup(srv.Close)
	return srv
}
