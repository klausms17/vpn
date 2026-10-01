package service

import "golang.zx2c4.com/wireguard/windows/tunnel/firewall"

// wfpHold is the engine's Hold: WireGuard's kill-switch rules, which let
// through only the service's own traffic, loopback, DHCP and neighbour
// discovery. They live in a dynamic WFP session, so they go with the
// service if it dies.
type wfpHold struct{}

// On has no tunnel adapter to let through: the core that restarts has
// none until it runs, and the hold ends then.
func (wfpHold) On() error { return firewall.EnableFirewall(0, false, nil) }

func (wfpHold) Off() { firewall.DisableFirewall() }
