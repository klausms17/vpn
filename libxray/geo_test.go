package libxray

import (
	"os"
	"path/filepath"
	"testing"
)

// Set GEO_DIR to a directory with the upstream runetfreedom geoip.dat and
// geosite.dat to run these tests.
func geoDir(t *testing.T) string {
	dir := os.Getenv("GEO_DIR")
	if dir == "" {
		t.Skip("GEO_DIR not set")
	}
	return dir
}

func TestTrimAndCheckGeo(t *testing.T) {
	dir := geoDir(t)
	out := t.TempDir()

	siteCodes := GeositeCodes
	ipCodes := GeoipCodes

	if err := TrimGeoFile(filepath.Join(dir, "geosite.dat"), filepath.Join(out, "geosite.dat"), siteCodes); err != nil {
		t.Fatal(err)
	}
	if err := TrimGeoFile(filepath.Join(dir, "geoip.dat"), filepath.Join(out, "geoip.dat"), ipCodes); err != nil {
		t.Fatal(err)
	}
	if err := CheckGeoFile(filepath.Join(out, "geosite.dat"), siteCodes); err != nil {
		t.Fatal(err)
	}
	if err := CheckGeoFile(filepath.Join(out, "geoip.dat"), ipCodes); err != nil {
		t.Fatal(err)
	}
	if err := CheckGeoFile(filepath.Join(out, "geoip.dat"), "ru,cn"); err == nil {
		t.Fatal("expected missing cn")
	}
	if err := TrimGeoFile(filepath.Join(dir, "geoip.dat"), filepath.Join(out, "bad.dat"), "ru,no-such-code"); err == nil {
		t.Fatal("expected error for unknown code")
	}
	if _, err := os.Stat(filepath.Join(out, "bad.dat")); !os.IsNotExist(err) {
		t.Fatal("failed trim must not leave a file behind")
	}
	for _, f := range []string{"geosite.dat", "geoip.dat"} {
		st, _ := os.Stat(filepath.Join(out, f))
		t.Logf("trimmed %s: %d bytes", f, st.Size())
	}

	ip := filepath.Join(out, "geoip.dat")
	cases := []struct {
		code, ip string
		want     bool
	}{
		{"ru", "77.88.8.8", true},
		{"ru", "8.8.8.8", false},
		{"private", "192.168.1.10", true},
		{"private", "fd00::1", true},
		{"ru", "2a02:6b8::feed:0ff", true},
		{"telegram", "149.154.167.51", true},
	}
	for _, c := range cases {
		got, err := GeoIPContains(ip, c.code, c.ip)
		if err != nil {
			t.Fatal(err)
		}
		if got != c.want {
			t.Errorf("%s in %s = %v, want %v", c.ip, c.code, got, c.want)
		}
	}
	if _, err := GeoIPContains(ip, "cn", "1.1.1.1"); err == nil {
		t.Error("expected error for missing category")
	}
}

func TestCheckGeoFileRejectsGarbage(t *testing.T) {
	p := filepath.Join(t.TempDir(), "x.dat")
	os.WriteFile(p, []byte("<html>rate limited</html>"), 0o644)
	if err := CheckGeoFile(p, "ru"); err == nil {
		t.Fatal("expected error")
	}
	os.WriteFile(p, nil, 0o644)
	if err := CheckGeoFile(p, "ru"); err == nil {
		t.Fatal("expected error for empty file")
	}
}
