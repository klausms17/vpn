package libxray

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestGeoReloadStampTracksFileChanges(t *testing.T) {
	dir := t.TempDir()
	for _, n := range []string{"geoip.dat", "geosite.dat"} {
		if err := os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	old := os.Getenv(envAsset)
	t.Cleanup(func() { _ = os.Setenv(envAsset, old); geoStamp.value = "" })
	_ = os.Setenv(envAsset, dir)
	geoStamp.value = ""

	if err := reloadGeoIfChanged(); err != nil {
		t.Fatal(err)
	}
	first := geoStamp.value
	if first == "" {
		t.Fatal("stamp not recorded on first start")
	}
	if err := reloadGeoIfChanged(); err != nil || geoStamp.value != first {
		t.Fatalf("unchanged files must keep the stamp: %v", err)
	}
	later := time.Now().Add(2 * time.Second)
	if err := os.WriteFile(filepath.Join(dir, "geoip.dat"), []byte("xy"), 0o600); err != nil {
		t.Fatal(err)
	}
	_ = os.Chtimes(filepath.Join(dir, "geoip.dat"), later, later)
	if err := reloadGeoIfChanged(); err != nil {
		t.Fatalf("reload after an update failed: %v", err)
	}
	if geoStamp.value == first {
		t.Fatal("stamp did not follow the updated file")
	}
}
