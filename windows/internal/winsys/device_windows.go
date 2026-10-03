package winsys

import (
	"fmt"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// MachineGUID is the id Windows gave this installation, or "" when it
// cannot be read. It stays across reinstalls of the app.
func MachineGUID() string {
	return regString(`SOFTWARE\Microsoft\Cryptography`, "MachineGuid")
}

// Version is the Windows version, as "10.0.26100".
func Version() string {
	v := windows.RtlGetVersion()
	return fmt.Sprintf("%d.%d.%d", v.MajorVersion, v.MinorVersion, v.BuildNumber)
}

// Hardware is the PC's maker and model, as its firmware names them.
func Hardware() (maker, model string) {
	const bios = `HARDWARE\DESCRIPTION\System\BIOS`
	return regString(bios, "SystemManufacturer"), regString(bios, "SystemProductName")
}

func regString(path, name string) string {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, path, registry.QUERY_VALUE|registry.WOW64_64KEY)
	if err != nil {
		return ""
	}
	defer k.Close()
	v, _, err := k.GetStringValue(name)
	if err != nil {
		return ""
	}
	return v
}
