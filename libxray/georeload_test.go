package libxray

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestGeoReloadOnlyWhenFilesChange(t *testing.T) {
	dir := t.TempDir()
	for _, n := range []string{"geoip.dat", "geosite.dat"} {
		if err := os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	oldEnv, oldReload := os.Getenv(envAsset), reloadGeo
	t.Cleanup(func() { _ = os.Setenv(envAsset, oldEnv); reloadGeo = oldReload; geoStamp.value = "" })
	_ = os.Setenv(envAsset, dir)
	geoStamp.value = ""
	calls, fail := 0, false
	reloadGeo = func() error {
		calls++
		if fail {
			return errors.New("broken")
		}
		return nil
	}

	reloadGeoIfChanged() // first core in the process: nothing to reload
	reloadGeoIfChanged() // unchanged
	if calls != 0 {
		t.Fatalf("reloaded %d times without a change", calls)
	}

	touch := func(content string, at time.Time) {
		p := filepath.Join(dir, "geoip.dat")
		if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		_ = os.Chtimes(p, at, at)
	}
	touch("xy", time.Now().Add(2*time.Second))
	fail = true
	reloadGeoIfChanged()
	reloadGeoIfChanged() // a failed reload is retried on the next start
	if calls != 2 {
		t.Fatalf("expected 2 attempts after a failed reload, got %d", calls)
	}
	fail = false
	reloadGeoIfChanged()
	reloadGeoIfChanged() // succeeded: no more reloads
	if calls != 3 {
		t.Fatalf("expected 3 reloads in total, got %d", calls)
	}
}
