package libxray

import (
	"net/http"
	"net/http/httptest"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Guards against regressions in start-up time and memory, which directly
// cost battery on the phone and matter for the 50 MB iOS extension limit.
// Run alone (MEM_TEST=1 go test -run TestStartupCost) for meaningful numbers.
func TestStartupCost(t *testing.T) {
	if os.Getenv("MEM_TEST") == "" {
		t.Skip("MEM_TEST not set")
	}
	useTrimmedGeo(t)
	p := realityProfile(t)
	limits := map[string]float64{ModeRuDirect: 25, ModeBlockedOnly: 30, ModeGlobal: 10}
	for _, mode := range []string{ModeRuDirect, ModeBlockedOnly, ModeGlobal} {
		cfg := buildOpts(t, BuildOptions{Outbounds: p.Outbounds, Mode: mode, SocksPort: 21081})
		runtime.GC()
		debug.FreeOSMemory()
		start := time.Now()
		c := NewController()
		if err := c.Start(cfg, 0); err != nil {
			t.Fatal(err)
		}
		took := time.Since(start)
		runtime.GC()
		var ms runtime.MemStats
		runtime.ReadMemStats(&ms)
		heap := float64(ms.HeapInuse) / 1e6
		t.Logf("mode=%s start=%v heapInUse=%.1fMB", mode, took.Round(time.Millisecond), heap)
		if heap > limits[mode] {
			t.Errorf("mode %s uses %.1f MB heap, limit %.0f MB", mode, heap, limits[mode])
		}
		if err := c.Stop(); err != nil {
			t.Fatal(err)
		}
	}
}

// A failover probe runs inside the VPN process next to the tunnel: 8
// candidates, 4 at a time, must stay cheap. The local servers run in the
// same process, so the numbers include their side of every handshake.
// Run alone: MEM_TEST=1 go test -run TestProbeCost
func TestProbeCost(t *testing.T) {
	if os.Getenv("MEM_TEST") == "" {
		t.Skip("MEM_TEST not set")
	}
	s := startProbeServers(t)
	dead := freePort(t)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer target.Close()
	var list []string
	for i := 0; i < 8; i++ {
		switch i % 4 {
		case 0, 1:
			list = append(list, outboundsJSON(t, mustParse(t, s.realityLink(s.portA))))
		case 2:
			list = append(list, s.chainJSON(t, s.portA))
		case 3:
			list = append(list, outboundsJSON(t, mustParse(t, s.realityLink(dead))))
		}
	}
	candidates := "[" + strings.Join(list, ",") + "]"
	c := NewController()
	probe := func() string {
		out, err := c.ProbeOutbounds(candidates, target.URL, 5000, 4)
		if err != nil {
			t.Fatal(err)
		}
		return out
	}
	// The first probe in a process also pays for one-time initialisation
	// (TLS/REALITY tables); the tunnel has done that already on the phone.
	probe()

	heapInUse := func() uint64 {
		var ms runtime.MemStats
		runtime.ReadMemStats(&ms)
		return ms.HeapInuse
	}
	runtime.GC()
	debug.FreeOSMemory()
	base := heapInUse()
	var peak atomic.Uint64
	stop := make(chan struct{})
	sampled := make(chan struct{})
	go func() {
		defer close(sampled)
		for {
			if h := heapInUse(); h > peak.Load() {
				peak.Store(h)
			}
			select {
			case <-stop:
				return
			case <-time.After(2 * time.Millisecond):
			}
		}
	}()
	start := time.Now()
	out := probe()
	took := time.Since(start)
	close(stop)
	<-sampled
	runtime.GC()
	retained := heapInUse()

	delta := (float64(peak.Load()) - float64(base)) / 1e6
	t.Logf("results=%s took=%v base=%.1fMB peak=+%.1fMB retained=%+.1fMB", out, took.Round(time.Millisecond),
		float64(base)/1e6, delta, (float64(retained)-float64(base))/1e6)
	if delta > 10 {
		t.Errorf("probing 8 candidates takes %.1f MB of heap, limit 10 MB", delta)
	}
	if grown := (float64(retained) - float64(base)) / 1e6; grown > 2 {
		t.Errorf("%.1f MB stay allocated after the probe", grown)
	}
}
