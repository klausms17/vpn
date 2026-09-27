package libxray

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
	"github.com/xtls/xray-core/features/stats"
)

type testKeys struct {
	UUID           string
	RealityPriv    string
	RealityPub     string
	MldsaSeed      string
	MldsaVerify    string
	VlessDecrypt   string // server side
	VlessEncrypt   string // client side
	SS2022Key      string
	TrojanPassword string
}

var (
	keysOnce sync.Once
	keys     testKeys
)

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func x25519Pair(t testing.TB) (priv, pub string) {
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return b64url(k.Bytes()), b64url(k.PublicKey().Bytes())
}

func getKeys(t testing.TB) testKeys {
	keysOnce.Do(func() {
		keys.UUID = "b831381d-6324-4d53-ad4f-8cda48b30811"
		keys.RealityPriv, keys.RealityPub = x25519Pair(t)
		var seed [mldsa65.SeedSize]byte
		rand.Read(seed[:])
		pub, _ := mldsa65.NewKeyFromSeed(&seed)
		keys.MldsaSeed = b64url(seed[:])
		keys.MldsaVerify = b64url(pub.Bytes())
		encPriv, encPub := x25519Pair(t)
		keys.VlessDecrypt = "mlkem768x25519plus.native.600s." + encPriv
		keys.VlessEncrypt = "mlkem768x25519plus.native.0rtt." + encPub
		k := make([]byte, 16)
		rand.Read(k)
		keys.SS2022Key = base64.StdEncoding.EncodeToString(k)
		keys.TrojanPassword = "p@ss:w0rd/+="
	})
	return keys
}

// trimmedGeoDir trims the upstream geo files from GEO_DIR once per test run
// and points Xray at the result.
var (
	geoOnce sync.Once
	geoOut  string
	geoErr  error
)

func useTrimmedGeo(t testing.TB) string {
	src := os.Getenv("GEO_DIR")
	if src == "" {
		t.Skip("GEO_DIR not set")
	}
	geoOnce.Do(func() {
		geoOut, geoErr = os.MkdirTemp("", "geo")
		if geoErr != nil {
			return
		}
		if geoErr = TrimGeoFile(filepath.Join(src, "geosite.dat"), filepath.Join(geoOut, "geosite.dat"), GeositeCodes); geoErr != nil {
			return
		}
		geoErr = TrimGeoFile(filepath.Join(src, "geoip.dat"), filepath.Join(geoOut, "geoip.dat"), GeoipCodes)
	})
	if geoErr != nil {
		t.Fatal(geoErr)
	}
	InitEnv(geoOut)
	return geoOut
}

func mustParse(t testing.TB, link string) *Profile {
	t.Helper()
	p, err := parseLink(link)
	if err != nil {
		t.Fatalf("parse %q: %v", link, err)
	}
	return p
}

func outbound(t testing.TB, p *Profile) map[string]any {
	t.Helper()
	var ob map[string]any
	if err := json.Unmarshal(p.Outbounds[0], &ob); err != nil {
		t.Fatal(err)
	}
	return ob
}

// dig walks nested maps/slices: dig(m, "a", "b", 0, "c").
func dig(v any, path ...any) any {
	for _, p := range path {
		switch k := p.(type) {
		case string:
			m, ok := v.(map[string]any)
			if !ok {
				return nil
			}
			v = m[k]
		case int:
			s, ok := v.([]any)
			if !ok || k >= len(s) {
				return nil
			}
			v = s[k]
		}
	}
	return v
}

func outboundsJSON(t testing.TB, p *Profile) string {
	b, err := json.Marshal(p.Outbounds)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// withStats turns on Xray's outbound byte counters in a built config, for
// tests that check which outbound carried the traffic (see takeTraffic).
func withStats(t *testing.T, cfg string) string {
	t.Helper()
	var c map[string]any
	if err := json.Unmarshal([]byte(cfg), &c); err != nil {
		t.Fatal(err)
	}
	c["stats"] = map[string]any{}
	c["policy"].(map[string]any)["system"] = map[string]any{"statsOutboundUplink": true, "statsOutboundDownlink": true}
	b, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// traffic is what the proxy and direct outbounds carried, in bytes.
type traffic struct{ proxyUp, proxyDown, directUp, directDown int64 }

// takeTraffic returns and resets the byte counters of the running core,
// started from a config passed through withStats.
func takeTraffic(t *testing.T, ctrl *Controller) traffic {
	t.Helper()
	ctrl.mu.Lock()
	inst := ctrl.cur.inst
	ctrl.mu.Unlock()
	sm := inst.GetFeature(stats.ManagerType()).(stats.Manager)
	take := func(tag, dir string) int64 {
		if c := sm.GetCounter("outbound>>>" + tag + ">>>traffic>>>" + dir); c != nil {
			return c.Set(0)
		}
		return 0
	}
	return traffic{take(ProxyTag, "uplink"), take(ProxyTag, "downlink"), take(DirectTag, "uplink"), take(DirectTag, "downlink")}
}

var base64Std = base64.StdEncoding

func t0() context.Context { return context.Background() }
