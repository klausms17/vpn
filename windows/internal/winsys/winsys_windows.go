// Package winsys holds the Windows calls of the service and the window:
// the protected data folder, DPAPI and elevation.
package winsys

import (
	"errors"
	"fmt"
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/conf/dpapi"
)

// dirSDDL opens a folder to SYSTEM and administrators only, and everything
// made inside it inherits that.
const dirSDDL = "O:SYG:SYD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"

// SecureDir makes dir exist, open to SYSTEM and administrators only, and
// sets that again on a folder that is there already. dir must be inside
// the program's own folder, which only administrators can change, so
// nobody else can have made it; a link there is refused all the same.
func SecureDir(dir string) error {
	fi, err := os.Lstat(dir)
	if errors.Is(err, os.ErrNotExist) {
		return create(dir)
	}
	if err != nil {
		return err
	}
	// Junctions show as irregular, symbolic links as links.
	if !fi.IsDir() || fi.Mode()&(os.ModeSymlink|os.ModeIrregular) != 0 {
		return fmt.Errorf("%s is not a plain folder", dir)
	}
	return protect(dir)
}

func create(dir string) error {
	sd, err := windows.SecurityDescriptorFromString(dirSDDL)
	if err != nil {
		return err
	}
	path, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return err
	}
	sa := &windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}
	return windows.CreateDirectory(path, sa)
}

// protect sets the folder's owner and permissions again; Windows passes
// them on to everything inside.
func protect(dir string) error {
	sd, err := windows.SecurityDescriptorFromString(dirSDDL)
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	return windows.SetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		owner, nil, dacl, nil)
}

// DPAPI seals files for this computer's SYSTEM account, as WireGuard
// stores its configurations: only SYSTEM on this PC can open them, and a
// copied file is of no use elsewhere. Name is sealed in and checked.
type DPAPI struct{ Name string }

func (d DPAPI) Seal(plain []byte) ([]byte, error)  { return dpapi.Encrypt(plain, d.Name) }
func (d DPAPI) Open(sealed []byte) ([]byte, error) { return dpapi.Decrypt(sealed, d.Name) }

// Elevated reports whether the process runs with administrator rights.
func Elevated() bool { return windows.GetCurrentProcessToken().IsElevated() }
