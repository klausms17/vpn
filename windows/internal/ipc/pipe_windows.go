package ipc

import (
	"context"
	"errors"
	"net"
	"os"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/ipc/namedpipe"
)

// PipePath is the service's pipe. Only administrators and SYSTEM can create
// pipes under ProtectedPrefix\Administrators, so no program can pose as the
// service, not even while it restarts.
const PipePath = `\\.\pipe\ProtectedPrefix\Administrators\KirovVPN\control`

// pipeSDDL gives SYSTEM and administrators full access, and signed-in
// users (Remote Desktop too) read and write without FILE_APPEND_DATA,
// which for pipes is FILE_CREATE_PIPE_INSTANCE. Network logons are denied.
const pipeSDDL = "O:SYG:SYD:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;IU)"

// clientAccess is what a window asks for: FILE_READ_DATA, FILE_WRITE_DATA,
// FILE_READ_ATTRIBUTES, READ_CONTROL and SYNCHRONIZE. GENERIC_WRITE would
// include FILE_APPEND_DATA, which users are not granted.
const clientAccess = 0x120083

// Listen creates the pipe. It fails if the pipe exists already.
func Listen() (net.Listener, error) {
	sd, err := windows.SecurityDescriptorFromString(pipeSDDL)
	if err != nil {
		return nil, err
	}
	cfg := namedpipe.ListenConfig{SecurityDescriptor: sd, InputBufferSize: 64 << 10, OutputBufferSize: 64 << 10}
	return cfg.Listen(PipePath)
}

// Dial connects to the service, waiting while every instance of the pipe
// is busy, until ctx ends.
func Dial(ctx context.Context) (*os.File, error) {
	path, err := windows.UTF16PtrFromString(PipePath)
	if err != nil {
		return nil, err
	}
	for {
		h, err := windows.CreateFile(path, clientAccess, 0, nil, windows.OPEN_EXISTING,
			windows.FILE_FLAG_OVERLAPPED|windows.SECURITY_SQOS_PRESENT|windows.SECURITY_IDENTIFICATION, 0)
		if err == nil {
			if err := checkOwner(h); err != nil {
				windows.CloseHandle(h)
				return nil, err
			}
			// Overlapped: reads and writes run concurrently and honour deadlines.
			return os.NewFile(uintptr(h), PipePath), nil
		}
		if !errors.Is(err, windows.ERROR_PIPE_BUSY) {
			return nil, err
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(20 * time.Millisecond):
		}
	}
}

// IsNotRunning reports whether a Dial error means the service is not running.
func IsNotRunning(err error) bool {
	return errors.Is(err, windows.ERROR_FILE_NOT_FOUND) || errors.Is(err, windows.ERROR_PATH_NOT_FOUND)
}

// checkOwner makes sure the pipe belongs to SYSTEM, as the service's does.
func checkOwner(h windows.Handle) error {
	sd, err := windows.GetSecurityInfo(h, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return err
	}
	system, err := windows.CreateWellKnownSid(windows.WinLocalSystemSid)
	if err != nil {
		return err
	}
	if !owner.Equals(system) {
		return errors.New("канал Kirov VPN создан не службой")
	}
	return nil
}
