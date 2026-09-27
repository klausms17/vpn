package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"flag"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func verifyJWT(t *testing.T, pub *ecdsa.PublicKey, tok, kid, iss string) {
	t.Helper()
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		t.Fatalf("token has %d parts", len(parts))
	}
	enc := base64.RawURLEncoding
	var h, p map[string]any
	hb, _ := enc.DecodeString(parts[0])
	pb, _ := enc.DecodeString(parts[1])
	_ = json.Unmarshal(hb, &h)
	_ = json.Unmarshal(pb, &p)
	if h["alg"] != "ES256" || h["kid"] != kid || p["iss"] != iss || p["aud"] != "appstoreconnect-v1" {
		t.Fatalf("bad claims %v %v", h, p)
	}
	if exp, iat := p["exp"].(float64), p["iat"].(float64); exp-iat > 20*60 {
		t.Fatalf("token lives %v s, API allows 20 min", exp-iat)
	}
	sig, _ := enc.DecodeString(parts[2])
	if len(sig) != 64 {
		t.Fatalf("signature is %d bytes", len(sig))
	}
	sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if !ecdsa.Verify(pub, sum[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])) {
		t.Fatal("JWT signature does not verify")
	}
}

func TestRegeneratesProfilesWithAllDevices(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(key)
	p8 := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))

	var mu sync.Mutex
	var calls []string
	var created []map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		verifyJWT(t, &key.PublicKey, strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), "KEY123", "issuer-uuid")
		mu.Lock()
		calls = append(calls, r.Method+" "+r.URL.Path+"?"+r.URL.RawQuery)
		mu.Unlock()
		switch {
		case r.Method == "GET" && r.URL.Path == "/v1/devices" && r.URL.Query().Get("filter[udid]") != "":
			io.WriteString(w, `{"data":[]}`)
		case r.Method == "POST" && r.URL.Path == "/v1/devices":
			io.WriteString(w, `{"data":{"id":"D3","type":"devices"}}`)
		case r.URL.Path == "/v1/certificates":
			io.WriteString(w, `{"data":[{"id":"C-old","type":"certificates","attributes":{"expirationDate":"2026-01-01T00:00:00.000+0000"}},{"id":"C1","type":"certificates","attributes":{"expirationDate":"2027-08-01T00:00:00.000+0000"}}]}`)
		case r.URL.Path == "/v1/devices":
			io.WriteString(w, `{"data":[{"id":"D1","type":"devices"},{"id":"D2","type":"devices"}]}`)
		case r.URL.Path == "/v1/bundleIds":
			// the identifier filter also returns the extension's ID
			io.WriteString(w, `{"data":[{"id":"B1","type":"bundleIds","attributes":{"identifier":"com.klausms.vpn"}},{"id":"B2","type":"bundleIds","attributes":{"identifier":"com.klausms.vpn.tunnel"}}]}`)
		case r.Method == "GET" && r.URL.Path == "/v1/profiles":
			io.WriteString(w, `{"data":[{"id":"P-old","type":"profiles","attributes":{"name":"`+r.URL.Query().Get("filter[name]")+`"}}]}`)
		case r.Method == "DELETE":
			w.WriteHeader(204)
		case r.Method == "POST" && r.URL.Path == "/v1/profiles":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			mu.Lock()
			created = append(created, body)
			mu.Unlock()
			io.WriteString(w, `{"data":{"id":"P-new","type":"profiles","attributes":{"uuid":"UUID-1","expirationDate":"2027-08-01","profileContent":"`+base64.StdEncoding.EncodeToString([]byte("PROFILE"))+`"}}}`)
		default:
			http.Error(w, "unexpected "+r.Method+" "+r.URL.Path, 500)
		}
	}))
	defer srv.Close()

	dir := t.TempDir()
	t.Setenv("ASC_KEY_P8", p8)
	t.Setenv("ASC_KEY_ID", "KEY123")
	t.Setenv("ASC_ISSUER_ID", "issuer-uuid")
	t.Setenv("ASC_API", srv.URL)
	flag.CommandLine = flag.NewFlagSet("test", flag.ContinueOnError)
	os.Args = []string{"asc-profiles", "-out", dir, "-profile", "com.klausms.vpn=Klaus VPN Ad Hoc", "-register", "00008110-000A1B2C3D4E5F6A=Friend iPhone"}
	start := time.Now()
	if err := run(); err != nil {
		t.Fatal(err)
	}
	t.Logf("calls: %v (%v)", calls, time.Since(start))

	if len(created) != 1 {
		t.Fatalf("created %d profiles", len(created))
	}
	data := created[0]["data"].(map[string]any)
	rel := data["relationships"].(map[string]any)
	if got := rel["bundleId"].(map[string]any)["data"].(map[string]any)["id"]; got != "B1" {
		t.Errorf("bundle id %v, want exact match B1", got)
	}
	if got := rel["certificates"].(map[string]any)["data"].([]any)[0].(map[string]any)["id"]; got != "C1" {
		t.Errorf("certificate %v, want the newest C1", got)
	}
	if n := len(rel["devices"].(map[string]any)["data"].([]any)); n != 2 {
		t.Errorf("%d devices in profile", n)
	}
	if pt := data["attributes"].(map[string]any)["profileType"]; pt != "IOS_APP_ADHOC" {
		t.Errorf("profileType %v", pt)
	}
	b, err := os.ReadFile(filepath.Join(dir, "UUID-1.mobileprovision"))
	if err != nil || string(b) != "PROFILE" {
		t.Fatalf("profile file: %q %v", b, err)
	}
	joined := strings.Join(calls, "\n")
	for _, want := range []string{"POST /v1/devices", "DELETE /v1/profiles/P-old", "filter%5BcertificateType%5D=DISTRIBUTION"} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing call %q", want)
		}
	}
}
