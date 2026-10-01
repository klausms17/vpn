// Package winsys holds the Windows calls of the service and the window:
// the protected data folder, DPAPI and elevation.
package winsys

import (
	"errors"
	"fmt"
	"os"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/conf/dpapi"
)

// dirSDDL opens a folder to SYSTEM and administrators only, and everything
// made inside it inherits that.
const dirSDDL = "O:SYG:SYD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"

// ProgramData is the folder for machine-wide data, C:\ProgramData usually.
func ProgramData() (string, error) {
	return windows.KnownFolderPath(windows.FOLDERID_ProgramData, windows.KF_FLAG_DEFAULT)
}

// SecureDir makes dir exist, open to SYSTEM and administrators only. A
// folder that is already there but belongs to someone else, or is a link
// elsewhere, is moved aside: anyone may create folders in ProgramData, and
// the owner of one could open it up again at any time. It returns where a
// folder was moved, if one was.
func SecureDir(dir string) (movedTo string, err error) {
	fi, err := os.Lstat(dir)
	if errors.Is(err, os.ErrNotExist) {
		return "", create(dir)
	}
	if err != nil {
		return "", err
	}
	// Junctions show as irregular, symbolic links as links.
	if fi.IsDir() && fi.Mode()&(os.ModeSymlink|os.ModeIrregular) == 0 {
		owner, err := ownerOf(dir)
		if err != nil {
			return "", err
		}
		if owner.IsWellKnown(windows.WinLocalSystemSid) || owner.IsWellKnown(windows.WinBuiltinAdministratorsSid) {
			return "", protect(dir)
		}
	}
	movedTo = fmt.Sprintf("%s.untrusted-%d", dir, time.Now().UnixMilli())
	if err := os.Rename(dir, movedTo); err != nil {
		return "", err
	}
	return movedTo, create(dir)
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

func ownerOf(path string) (*windows.SID, error) {
	sd, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION)
	if err != nil {
		return nil, err
	}
	owner, _, err := sd.Owner()
	return owner, err
}

// DPAPI seals files for this computer's SYSTEM account, as WireGuard
// stores its configurations: only SYSTEM on this PC can open them, and a
// copied file is of no use elsewhere. Name is sealed in and checked.
type DPAPI struct{ Name string }

func (d DPAPI) Seal(plain []byte) ([]byte, error)  { return dpapi.Encrypt(plain, d.Name) }
func (d DPAPI) Open(sealed []byte) ([]byte, error) { return dpapi.Decrypt(sealed, d.Name) }

// Elevated reports whether the process runs with administrator rights.
func Elevated() bool { return windows.GetCurrentProcessToken().IsElevated() }
