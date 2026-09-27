package libxray

import (
	"net/netip"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
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
		{"ru", "::ffff:77.88.8.8", true},
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

// writeGeoIP replaces the geoip.dat at path by rename, the way the app
// updates it, and sets its modification time.
func writeGeoIP(t *testing.T, path string, mtime time.Time, entries ...*geodata.GeoIP) {
	t.Helper()
	b, err := proto.Marshal(&geodata.GeoIPList{Entry: entries})
	if err != nil {
		t.Fatal(err)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(tmp, mtime, mtime); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(tmp, path); err != nil {
		t.Fatal(err)
	}
}

func geoIPEntry(code string, reverse bool, cidrs ...string) *geodata.GeoIP {
	e := &geodata.GeoIP{Code: code, ReverseMatch: reverse}
	for _, c := range cidrs {
		p := netip.MustParsePrefix(c)
		e.Cidr = append(e.Cidr, &geodata.CIDR{Ip: p.Addr().AsSlice(), Prefix: uint32(p.Bits())})
	}
	return e
}

func TestGeoIPCache(t *testing.T) {
	path := filepath.Join(t.TempDir(), "geoip.dat")
	mtime := time.Now().Add(-time.Hour)
	writeGeoIP(t, path, mtime,
		geoIPEntry("RU-WHITELIST", false, "10.0.0.0/8", "2001:db8::/32"),
		geoIPEntry("NOT-LAN", true, "192.168.0.0/16"),
	)
	cases := []struct {
		code, ip string
		want     bool
	}{
		{"ru-whitelist", "10.1.2.3", true},
		{"ru-whitelist", "::ffff:10.1.2.3", true},
		{"ru-whitelist", "11.1.2.3", false},
		{"ru-whitelist", "2001:db8::1", true},
		{"ru-whitelist", "2001:db9::1", false},
		{"not-lan", "192.168.1.1", false},
		{"not-lan", "::ffff:192.168.1.1", false},
		{"not-lan", "8.8.8.8", true},
	}
	ask := func(code, ip string) bool {
		t.Helper()
		got, err := GeoIPContains(path, code, ip)
		if err != nil {
			t.Fatal(err)
		}
		return got
	}
	// Uncached: every answer from a fresh parse.
	for _, c := range cases {
		dropGeoIPCache()
		if got := ask(c.code, c.ip); got != c.want {
			t.Errorf("uncached: %s in %s = %v, want %v", c.ip, c.code, got, c.want)
		}
	}
	// Cached: the same answers, and a category is parsed only once.
	cached := func() *geoIPSet {
		geoIPCache.Lock()
		defer geoIPCache.Unlock()
		return geoIPCache.set
	}
	for _, code := range []string{"ru-whitelist", "not-lan"} {
		dropGeoIPCache()
		var parsed *geoIPSet
		for _, c := range cases {
			if c.code != code {
				continue
			}
			if got := ask(c.code, c.ip); got != c.want {
				t.Errorf("cached: %s in %s = %v, want %v", c.ip, c.code, got, c.want)
			}
			if parsed == nil {
				parsed = cached()
			} else if cached() != parsed {
				t.Errorf("%s was parsed again", code)
			}
		}
	}
	if v, err := HostInGeoIP(path, "ru-whitelist", "[2001:db8::1]", 1000); err != nil || v != 1 {
		t.Errorf("HostInGeoIP = %d, %v", v, err)
	}

	// A new file, even of the same size, replaces the cached category.
	size := func() int64 {
		fi, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		return fi.Size()
	}
	before := size()
	writeGeoIP(t, path, mtime.Add(time.Minute),
		geoIPEntry("RU-WHITELIST", false, "11.0.0.0/8", "2001:db8::/32"),
		geoIPEntry("NOT-LAN", true, "192.168.0.0/16"),
	)
	if size() != before {
		t.Fatal("the test needs a file of the same size")
	}
	if ask("ru-whitelist", "10.1.2.3") || !ask("ru-whitelist", "11.1.2.3") {
		t.Error("the cache outlived the file it came from")
	}
}
