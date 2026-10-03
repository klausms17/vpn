package applog

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLinesAndRotation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "service.log")
	l := New(path)
	l.now = func() time.Time { return time.Date(2026, 10, 1, 9, 5, 7, 0, time.UTC) }
	l.Info("tunnel up")
	l.Error("tunnel start failed")
	data, _ := os.ReadFile(path)
	if string(data) != "10-01 09:05:07 I tunnel up\n10-01 09:05:07 E tunnel start failed\n" {
		t.Errorf("log %q", data)
	}
	// Go's log package writes through it too.
	std := log.New(l, "", 0)
	std.Print("wintun: first\nsecond")
	data, _ = os.ReadFile(path)
	if !strings.HasSuffix(string(data), "I wintun: first\n10-01 09:05:07 I second\n") {
		t.Errorf("log %q", data)
	}

	for i := 0; i < MaxBytes/20+10; i++ {
		l.Info(fmt.Sprintf("line %d", i))
	}
	old, err := os.Stat(path + ".1")
	if err != nil || old.Size() <= MaxBytes {
		t.Fatalf("old file: %v %v", old, err)
	}
	if cur, _ := os.Stat(path); cur.Size() >= MaxBytes {
		t.Errorf("current file %d bytes", cur.Size())
	}
}

func TestAnUnwritableLogIsIgnored(t *testing.T) {
	l := New(filepath.Join(t.TempDir(), "missing", "service.log"))
	l.Info("nobody hears this") // no panic, no error
}

func TestAddressesAndNamesAreMasked(t *testing.T) {
	path := filepath.Join(t.TempDir(), "service.log")
	New(path).Warn("pin failed: dial tcp 203.0.113.7:443")
	New(path).Error("core did not start: tls: server name cdn.Example.com, Xray 26.9.30")
	data, _ := os.ReadFile(path)
	if !strings.Contains(string(data), " W pin failed: dial tcp [IP]:443\n") ||
		!strings.Contains(string(data), " E core did not start: tls: server name [host], Xray 26.9.30\n") {
		t.Errorf("log %q", data)
	}
}

func TestTailJoinsTheMovedLogAndMasks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "xray.log")
	os.WriteFile(path+".1", []byte("old line\nfailed to dial 203.0.113.7:443\n"), 0o600)
	os.WriteFile(path, []byte("lookup rutracker.org failed\n"), 0o600)
	if got := Tail(path, 1<<10, true); got != "old line\nfailed to dial [IP]:443\nlookup [host] failed\n" {
		t.Errorf("core log %q", got)
	}
	// Without hosts the names stay; the limit keeps the newest lines.
	if got := Tail(path, 60, false); got != "failed to dial [IP]:443\nlookup rutracker.org failed\n" {
		t.Errorf("app log %q", got)
	}
	if got := Tail(filepath.Join(t.TempDir(), "missing.log"), 100, false); got != "" {
		t.Errorf("missing log %q", got)
	}
}
