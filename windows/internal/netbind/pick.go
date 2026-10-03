// Package netbind keeps the service's own connections outside its tunnel,
// as Android's addDisallowedApplication(self) keeps the app out. While the
// tunnel runs, every socket the process opens is bound to the interface
// Windows would use without the tunnel, and the process's own name lookups
// go to that network's DNS servers instead of Windows' DNS Client, which
// would send them into the tunnel.
package netbind

// Route is a default route as Windows lists it.
type Route struct {
	IPv6  bool
	LUID  uint64
	Index uint32
	// Metric is the route's metric plus its interface's, which Windows
	// compares.
	Metric uint32
	Up     bool
}

// Choice is the interface to bind to, by index, for each IP version; 0
// means there is none.
type Choice struct{ V4, V6 uint32 }

// Pick returns, for each IP version, the interface of the default route
// with the lowest metric, as Windows itself picks it, leaving out the
// tunnel and interfaces that are down. Ties go to the lower index, so the
// choice does not flap. Unlike Xray's own binder it prefers no medium:
// Windows' metrics already rank cable above Wi-Fi.
func Pick(routes []Route, tunnel uint64) Choice {
	var c Choice
	best := [2]uint32{^uint32(0), ^uint32(0)}
	for _, r := range routes {
		if !r.Up || r.Index == 0 || (tunnel != 0 && r.LUID == tunnel) {
			continue
		}
		family, chosen := 0, &c.V4
		if r.IPv6 {
			family, chosen = 1, &c.V6
		}
		if r.Metric < best[family] || r.Metric == best[family] && r.Index < *chosen {
			best[family] = r.Metric
			*chosen = r.Index
		}
	}
	return c
}
