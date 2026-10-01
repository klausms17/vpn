package libxray

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/errors"
)

func TestTheCoreLogNamesNoSiteAndNoAddress(t *testing.T) {
	path := filepath.Join(t.TempDir(), "xray.log")
	cfg, _ := json.Marshal(map[string]any{
		"log":       map[string]any{"loglevel": "warning", "error": path},
		"outbounds": []any{map[string]any{"protocol": "freedom"}},
	})
	inst, err := newInstance(string(cfg))
	if err != nil {
		t.Fatal(err)
	}
	if err := inst.Start(); err != nil {
		t.Fatal(err)
	}
	defer inst.Close()
	errors.LogWarning(context.Background(), "app/dns: failed to retrieve response for rutracker.org from 203.0.113.7:53")
	var data []byte
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		if data, _ = os.ReadFile(path); strings.Contains(string(data), "failed to retrieve") {
			break
		}
	}
	if !strings.Contains(string(data), "app/dns: failed to retrieve response for [host] from [IP]:53") {
		t.Errorf("log %q", data)
	}
}
