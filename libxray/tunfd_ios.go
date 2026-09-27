//go:build ios

package libxray

import (
	"runtime/debug"
	"strings"

	"golang.org/x/sys/unix"
)

// TunnelFD returns the utun descriptor that NetworkExtension opened for
// this packet tunnel provider, or -1 when there is none. Call it after
// setTunnelNetworkSettings has completed (the interface exists only then).
// It looks for the kernel control socket of utun among the process's fds,
// as WireGuard and sing-box do; the provider's private "socket" key path is
// not used, since a changed key would crash the extension.
func TunnelFD() int32 {
	info := &unix.CtlInfo{}
	copy(info.Name[:], "com.apple.net.utun_control")
	for fd := 0; fd < 1024; fd++ {
		sa, err := unix.Getpeername(fd)
		if err != nil {
			continue
		}
		ctl, ok := sa.(*unix.SockaddrCtl)
		if !ok {
			continue
		}
		if info.Id == 0 {
			if err := unix.IoctlCtlInfo(fd, info); err != nil {
				continue
			}
		}
		if ctl.ID != info.Id {
			continue
		}
		// SYSPROTO_CONTROL, UTUN_OPT_IFNAME
		if name, err := unix.GetsockoptString(fd, 2, 2); err == nil && strings.HasPrefix(name, "utun") {
			return int32(fd)
		}
	}
	return -1
}

// SetMemoryLimit fits the Go runtime into the Network Extension's memory
// budget (about 50 MB for the whole extension): a soft limit for the heap
// and how eagerly the collector runs.
func SetMemoryLimit(bytes int64, gcPercent int32) {
	if gcPercent > 0 {
		steadyGC = int(gcPercent)
		debug.SetGCPercent(steadyGC)
	}
	if bytes > 0 {
		debug.SetMemoryLimit(bytes)
	}
}

// steadyGC is the collector setting outside of Start.
var steadyGC = 100

// prepareTunFd hands Xray its own copy of the provider's descriptor. On
// darwin Xray wraps the fd from the environment in an os.File whose
// finalizer closes it some time after Stop: with the original fd that would
// close the tunnel under the next core. The copy is what gets closed.
func prepareTunFd(fd int32) (int32, error) {
	if fd <= 0 {
		return 0, nil
	}
	d, err := unix.Dup(int(fd))
	if err != nil {
		return 0, err
	}
	unix.CloseOnExec(d)
	return int32(d), nil
}

// startGC collects hard while a config is parsed (geo lists make a lot of
// short-lived garbage, and the extension must not cross its limit at the
// peak); the returned func restores the steady setting.
func startGC() func() {
	debug.SetGCPercent(10)
	return func() {
		debug.SetGCPercent(steadyGC)
		debug.FreeOSMemory()
	}
}
