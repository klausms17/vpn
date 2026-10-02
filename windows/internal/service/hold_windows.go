package service

import "golang.zx2c4.com/wireguard/windows/tunnel/firewall"

// wfpHoldOn turns on WireGuard's kill-switch rules, which let through only
// the service's own traffic, loopback, DHCP and neighbour discovery. They
// live in a dynamic WFP session, so they go with the service if it dies.
// No tunnel adapter is let through: the core that restarts has none until
// it runs, and the hold ends then.
func wfpHoldOn() error { return firewall.EnableFirewall(0, false, nil) }

func wfpHoldOff() { firewall.DisableFirewall() }
