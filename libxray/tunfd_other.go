//go:build !ios

package libxray

// On Android the VPN service owns the TUN fd and Xray only keeps its number
// (proxy/tun/tun_android.go), so it is passed as it is.
func prepareTunFd(fd int32) (int32, error) { return fd, nil }

func startGC() func() { return func() {} }
