package redact

import "testing"

func TestIPs(t *testing.T) {
	for in, want := range map[string]string{
		"dial tcp 203.0.113.7:443: i/o timeout":         "dial tcp [IP]:443: i/o timeout",
		"dial tcp [2001:db8::1]:443: connect refused":   "dial tcp [[IP]]:443: connect refused",
		"lookup on fe80::1%12 and ::ffff:198.51.100.2.": "lookup on [IP] and ::ffff:[IP].",
		"tcp:192.0.2.1:8443 and udp:[::1]:53":           "tcp:[IP]:8443 and udp:[[IP]]:53",
		// Not addresses: times, versions, interface numbers.
		"service 1.0.62 starting at 09:05:07, Xray 26.9.30": "service 1.0.62 starting at 09:05:07, Xray 26.9.30",
		"network: IPv4 via interface 12, IPv6 via 7":        "network: IPv4 via interface 12, IPv6 via 7",
		"999.1.1.1 is no address":                           "999.1.1.1 is no address",
	} {
		if got := IPs(in); got != want {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}
}

func TestHosts(t *testing.T) {
	for in, want := range map[string]string{
		"app/dns: failed to retrieve response for rutracker.org > timeout": "app/dns: failed to retrieve response for [host] > timeout",
		"returning nil for domain vpn.example.com":                         "returning nil for domain [host]",
		"failed to dial tcp:www.example.co.uk:443":                         "failed to dial tcp:[host]:443",
		"domain full:vpn.example.com will use the first DNS":               "domain full:[host] will use the first DNS",
		"сайт.рф and xn--80ak6aa92e.com":                                   "[host] and [host]",
		"x509: certificate is valid for a.example.net, not b.example.net":  "x509: certificate is valid for [host], not [host]",
		// Not names: versions, components, abbreviations.
		"Xray 26.9.30 started, proxy/vless/outbound: e.g. EOF": "Xray 26.9.30 started, proxy/vless/outbound: e.g. EOF",
	} {
		if got := Hosts(in); got != want {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}
}
