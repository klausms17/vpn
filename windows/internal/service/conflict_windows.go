package service

import (
	"crypto/md5"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// readRoutes reads Windows' IPv4 routes and adapters for findConflicts:
// none while the tunnel's adapter is missing.
func readRoutes() routeTable {
	tunnel, err := tunnelLUID()
	if err != nil {
		return routeTable{}
	}
	ifRows, err := winipcfg.GetIfTable2Ex(winipcfg.MibIfEntryNormalWithoutStatistics)
	if err != nil {
		return routeTable{}
	}
	ipRows, err := winipcfg.GetIPInterfaceTable(windows.AF_INET)
	if err != nil {
		return routeTable{}
	}
	rows, err := winipcfg.GetIPForwardTable2(windows.AF_INET)
	if err != nil {
		return routeTable{}
	}
	t := routeTable{tunnel: uint64(tunnel), routes: make([]route, 0, len(rows)), adapters: make(map[uint64]netAdapter, len(ifRows))}
	for i := range ifRows {
		a := &ifRows[i]
		t.adapters[uint64(a.InterfaceLUID)] = netAdapter{
			name:        a.Alias(),
			description: a.Description(),
			up:          a.OperStatus == winipcfg.IfOperStatusUp,
			// What PowerShell's Get-NetAdapter -Physical lists, a virtual
			// machine's network card too. VPNs' adapters (wintun,
			// WireGuard, TAP-Windows) are of another type, or Ethernet
			// with neither flag.
			physical: a.InterfaceAndOperStatusFlags&(winipcfg.IAOSFHardwareInterface|winipcfg.IAOSFConnectorPresent) != 0 &&
				(a.Type == winipcfg.IfTypeEthernetCSMACD || a.Type == winipcfg.IfTypeIEEE80211),
			loopback: a.Type == winipcfg.IfTypeSoftwareLoopback,
		}
	}
	metrics := make(map[winipcfg.LUID]uint32, len(ipRows))
	for i := range ipRows {
		metrics[ipRows[i].InterfaceLUID] = ipRows[i].Metric
	}
	for i := range rows {
		r := &rows[i]
		metric, ok := metrics[r.InterfaceLUID]
		if !ok {
			continue
		}
		t.routes = append(t.routes, route{
			prefix: r.DestinationPrefix.Prefix().Masked(),
			luid:   uint64(r.InterfaceLUID),
			metric: r.Metric + metric,
			local:  r.Protocol == winipcfg.RouteProtocolLocal,
		})
	}
	return t
}

// watchRouteChanges calls changed on each change of Windows' routes or IP
// interfaces until the returned stop.
func watchRouteChanges(changed func()) (stop func(), err error) {
	routes, err := winipcfg.RegisterRouteChangeCallback(func(winipcfg.MibNotificationType, *winipcfg.MibIPforwardRow2) { changed() })
	if err != nil {
		return nil, err
	}
	interfaces, err := winipcfg.RegisterInterfaceChangeCallback(func(winipcfg.MibNotificationType, *winipcfg.MibIPInterfaceRow) { changed() })
	if err != nil {
		_ = routes.Unregister()
		return nil, err
	}
	return func() {
		_ = routes.Unregister()
		_ = interfaces.Unregister()
	}, nil
}

// tunnelLUID finds the tunnel's adapter by the GUID Xray derives from its
// name, as netbind does: wintun gives the adapter another name when
// Windows still has one by that name.
func tunnelLUID() (winipcfg.LUID, error) {
	id := md5.Sum([]byte(adapter))
	return winipcfg.LUIDFromGUID((*windows.GUID)(unsafe.Pointer(&id[0])))
}
