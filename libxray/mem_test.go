package libxray

import (
	"os"
	"runtime"
	"runtime/debug"
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
