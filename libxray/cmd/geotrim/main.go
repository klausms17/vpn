// Command geotrim cuts the upstream runetfreedom geoip.dat/geosite.dat down
// to the categories the app uses and validates the result. Used by CI to
// produce the databases bundled in the APK.
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"github.com/klausms17/vpn/libxray"
)

func main() {
	src := flag.String("src", "", "directory with upstream geoip.dat and geosite.dat")
	dst := flag.String("dst", "", "output directory")
	flag.Parse()
	if *src == "" || *dst == "" {
		flag.Usage()
		os.Exit(2)
	}
	if err := os.MkdirAll(*dst, 0o755); err != nil {
		fail(err)
	}
	for _, f := range []struct{ name, codes string }{
		{"geoip.dat", libxray.GeoipCodes},
		{"geosite.dat", libxray.GeositeCodes},
	} {
		out := filepath.Join(*dst, f.name)
		if err := libxray.TrimGeoFile(filepath.Join(*src, f.name), out, f.codes); err != nil {
			fail(err)
		}
		if err := libxray.CheckGeoFile(out, f.codes); err != nil {
			fail(err)
		}
		st, _ := os.Stat(out)
		fmt.Printf("%s: %d bytes (%s)\n", out, st.Size(), f.codes)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "geotrim:", err)
	os.Exit(1)
}
