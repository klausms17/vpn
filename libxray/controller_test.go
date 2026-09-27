package libxray

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// A settings change restarts the core while a check through it may still
// be running (the one after the previous start). Stop must end that check
// at once instead of closing the core under it, and the next Start must
// work.
func TestStopEndsRunningCallsAndStartsAgain(t *testing.T) {
	// Accepts and never answers: a delay test through it hangs.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var held []net.Conn
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			held = append(held, conn)
			mu.Unlock()
		}
	}()
	t.Cleanup(func() {
		ln.Close()
		mu.Lock()
		for _, c := range held {
			c.Close()
		}
		mu.Unlock()
	})
	cfg := fmt.Sprintf(`{"log":{"loglevel":"none"},"outbounds":[{"tag":"proxy","protocol":"freedom","settings":{"redirect":"%s"}}]}`, ln.Addr())

	c := NewController()
	if err := c.Start(cfg, 0); err != nil {
		t.Fatal(err)
	}
	type result struct {
		err error
		at  time.Time
	}
	calls := make(chan result, 2)
	go func() {
		_, err := c.MeasureDelay("http://example.com/", 20000)
		calls <- result{err, time.Now()}
	}()
	go func() {
		_, err := c.FetchThroughTunnel("http://example.com/sub", "test", "", 20000)
		calls <- result{err, time.Now()}
	}()
	time.Sleep(300 * time.Millisecond)

	began := time.Now()
	if err := c.Stop(); err != nil {
		t.Fatalf("stop: %v", err)
	}
	if took := time.Since(began); took > 2*time.Second {
		t.Fatalf("stop took %v", took)
	}
	for i := 0; i < 2; i++ {
		select {
		case r := <-calls:
			if r.err == nil {
				t.Fatal("a call through the stopped core succeeded")
			}
		case <-time.After(2 * time.Second):
			t.Fatal("a call was still running after Stop")
		}
	}
	if _, err := c.MeasureDelay("http://example.com/", 1000); err == nil {
		t.Fatal("MeasureDelay without a running core succeeded")
	}

	if err := c.Start(cfg, 0); err != nil {
		t.Fatalf("start after stop: %v", err)
	}
	if !c.IsRunning() {
		t.Fatal("not running after the second start")
	}
	if err := c.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := c.Stop(); err != nil {
		t.Fatalf("second stop: %v", err)
	}
}

// A panic that ends the process (as in a background goroutine of the
// core) leaves its report in the crash log.
func TestCrashLogKeepsTheReport(t *testing.T) {
	if path := os.Getenv("KLAUS_CRASH_CHILD"); path != "" {
		if err := SetCrashLog(path); err != nil {
			fmt.Fprintln(os.Stderr, "SetCrashLog:", err)
			os.Exit(3)
		}
		go func() { panic("boom in a core goroutine") }()
		time.Sleep(5 * time.Second)
		os.Exit(0)
	}
	path := filepath.Join(t.TempDir(), "go-crash.log")
	cmd := exec.Command(os.Args[0], "-test.run=^TestCrashLogKeepsTheReport$")
	cmd.Env = append(os.Environ(), "KLAUS_CRASH_CHILD="+path)
	out, err := cmd.CombinedOutput()
	if err == nil {
		t.Fatalf("the child did not crash: %s", out)
	}
	data, rerr := os.ReadFile(path)
	if rerr != nil {
		t.Fatal(rerr)
	}
	if !strings.Contains(string(data), "boom in a core goroutine") {
		t.Fatalf("crash log lacks the panic: %q", data)
	}
}
