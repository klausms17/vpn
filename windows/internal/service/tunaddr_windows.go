package service

import (
	"fmt"
	"net/netip"

	"github.com/klausms17/vpn/libxray"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// tunAddress is the tunnel adapter's IPv4 address.
var tunAddress = netip.MustParseAddr(libxray.TunIPv4)

// holder is an adapter, not the tunnel's, that has the tunnel's address.
type holder struct {
	name   string
	up     bool
	luid   winipcfg.LUID
	prefix netip.Prefix
}

func tunAddressHolders() []holder {
	adapters, err := winipcfg.GetAdaptersAddresses(windows.AF_INET, winipcfg.GAAFlagDefault)
	if err != nil {
		return nil
	}
	var holders []holder
	for _, a := range adapters {
		if a.FriendlyName() == adapter {
			continue
		}
		for u := a.FirstUnicastAddress; u != nil; u = u.Next {
			if ip, ok := netip.AddrFromSlice(u.Address.IP()); ok && ip.Unmap() == tunAddress {
				holders = append(holders, holder{
					name:   a.FriendlyName(),
					up:     a.OperStatus == winipcfg.IfOperStatusUp,
					luid:   a.LUID,
					prefix: netip.PrefixFrom(tunAddress, int(u.OnLinkPrefixLength)),
				})
			}
		}
	}
	return holders
}

// addressHolder names a connected adapter that has the tunnel's address,
// most likely another VPN's, or "" if there is none.
func addressHolder() string {
	for _, h := range tunAddressHolders() {
		if h.up {
			return h.name
		}
	}
	return ""
}

// claimTunAddress readies the tunnel's address for the core. It takes the
// address off adapters that are not connected, as WireGuard does: one left
// behind by another program would keep the tunnel from getting it. A
// connected adapter that has it, another VPN's most likely, has to go
// first, whatever error Windows would give (addressTaken).
func claimTunAddress(log func(string)) error {
	var taken error
	for _, h := range tunAddressHolders() {
		switch {
		case h.up:
			taken = addressTaken{h.name}
		case h.luid.DeleteIPAddress(h.prefix) == nil:
			log(fmt.Sprintf("took the tunnel's address off the disconnected adapter %q", h.name))
		}
	}
	return taken
}

// ipv6Off reports whether IPv6 is switched off on Windows' ordinary (not
// tunnel) interfaces, which the tunnel's adapter is one of.
func ipv6Off() bool {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SYSTEM\CurrentControlSet\Services\Tcpip6\Parameters`, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	v, _, err := k.GetIntegerValue("DisabledComponents")
	return err == nil && v&0x10 != 0
}
