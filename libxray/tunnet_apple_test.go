package libxray

import (
	"encoding/json"
	"testing"
)

func TestTunSettingsApple(t *testing.T) {
	var cfg AppleTunConfig
	if err := json.Unmarshal([]byte(TunSettingsApple(true)), &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.MTU != TunMTU {
		t.Fatalf("mtu %d, the tun inbound reads %d", cfg.MTU, TunMTU)
	}
	if cfg.IPv4.Address != "198.18.0.1" || cfg.IPv4.Mask != "255.255.255.252" {
		t.Fatalf("interface %+v", cfg.IPv4)
	}
	want := map[string]string{
		"10.0.0.0": "255.0.0.0", "100.64.0.0": "255.192.0.0", "127.0.0.0": "255.0.0.0",
		"169.254.0.0": "255.255.0.0", "172.16.0.0": "255.240.0.0", "192.0.0.0": "255.255.255.0",
		"192.168.0.0": "255.255.0.0", "224.0.0.0": "240.0.0.0", "240.0.0.0": "240.0.0.0",
	}
	if len(cfg.IPv4Out) != len(want) {
		t.Fatalf("excluded %v", cfg.IPv4Out)
	}
	for _, r := range cfg.IPv4Out {
		if want[r.Address] != r.Mask {
			t.Fatalf("excluded route %+v, want mask %q", r, want[r.Address])
		}
	}
	if cfg.IPv6 != TunIPv6 || cfg.IPv6Prefix != 126 || len(cfg.IPv6In) != 1 || cfg.IPv6In[0].Address != "2000::" || cfg.IPv6In[0].Prefix != 3 {
		t.Fatalf("ipv6 %+v", cfg)
	}
	if len(cfg.DNSServers) != 1 || cfg.DNSServers[0] != TunDNSv4 {
		t.Fatalf("dns %v", cfg.DNSServers)
	}
}
