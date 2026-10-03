// Command mkres writes the Windows resources of both programs (icon,
// manifest, version) as .syso files, which go build links into the exes.
// CI runs it from windows/ before building: go run ./tools/mkres 1.0.N
package main

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"regexp"

	"github.com/tc-hib/winres"
	"github.com/tc-hib/winres/version"
)

// langRussian is the language of the version strings.
const langRussian = 0x0419

var programs = []struct {
	dir, file, description string
}{
	{"cmd/kirovvpn", "KirovVPN.exe", "Kirov VPN"},
	{"cmd/kirovvpn-service", "KirovVPNService.exe", "Служба Kirov VPN"},
}

func main() {
	if len(os.Args) != 2 || !regexp.MustCompile(`^\d+\.\d+\.\d+$`).MatchString(os.Args[1]) {
		log.Fatal("usage: mkres 1.0.N")
	}
	ver := os.Args[1]
	f, err := os.Open(filepath.Join("assets", "app.ico"))
	if err != nil {
		log.Fatal(err)
	}
	icon, err := winres.LoadICO(f)
	f.Close()
	if err != nil {
		log.Fatal(err)
	}
	for _, p := range programs {
		rs := winres.ResourceSet{}
		if err := rs.SetIcon(winres.ID(1), icon); err != nil {
			log.Fatal(err)
		}
		rs.SetManifest(winres.AppManifest{
			Compatibility:       winres.Win10AndAbove,
			ExecutionLevel:      winres.AsInvoker,
			DPIAwareness:        winres.DPIPerMonitorV2,
			UseCommonControlsV6: true,
			LongPathAware:       true,
		})
		vi := version.Info{}
		vi.SetFileVersion(ver)
		vi.SetProductVersion(ver)
		for key, value := range map[string]string{
			version.ProductName:      "Kirov VPN",
			version.CompanyName:      "Kirov VPN",
			version.FileDescription:  p.description,
			version.OriginalFilename: p.file,
			version.InternalName:     p.file,
			version.LegalCopyright:   "© 2026 Kirov VPN",
		} {
			if err := vi.Set(langRussian, key, value); err != nil {
				log.Fatal(err)
			}
		}
		rs.SetVersionInfo(vi)
		out, err := os.Create(filepath.Join(p.dir, "rsrc_windows_amd64.syso"))
		if err != nil {
			log.Fatal(err)
		}
		if err := rs.WriteObject(out, winres.ArchAMD64); err != nil {
			log.Fatal(err)
		}
		if err := out.Close(); err != nil {
			log.Fatal(err)
		}
		fmt.Println("resources of", p.file, ver)
	}
}
