package service

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/klausms17/vpn/libxray/client/importer"
	"github.com/klausms17/vpn/libxray/client/store"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

func TestTheConfigHasTheWindowsTunnel(t *testing.T) {
	keys, _, err := importer.Keys(context.Background(), "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome#DE", importer.ForService)
	if err != nil || len(keys) != 1 {
		t.Fatal(keys, err)
	}
	logFile := filepath.Join(t.TempDir(), "xray.log")
	settings := defaultSettings()
	settings.DirectSites = []string{"bank.example"}
	settings.DirectPrograms = []string{"Telegram.exe"}
	cfg, err := buildConfig(logFile, func() ipc.Settings { return settings })(keys[0].Stored("id", "", 0))
	if err != nil {
		t.Fatal(err)
	}
	var c struct {
		Log      map[string]any `json:"log"`
		Inbounds []struct {
			Protocol string         `json:"protocol"`
			Settings map[string]any `json:"settings"`
		} `json:"inbounds"`
		Routing struct {
			Rules []map[string]any `json:"rules"`
		} `json:"routing"`
	}
	if err := json.Unmarshal([]byte(cfg), &c); err != nil {
		t.Fatal(err)
	}
	if c.Log["error"] != logFile || c.Log["loglevel"] != "warning" {
		t.Errorf("log %v", c.Log)
	}
	if len(c.Inbounds) != 1 || c.Inbounds[0].Protocol != "tun" || c.Inbounds[0].Settings["name"] != "Kirov VPN" {
		t.Errorf("inbounds %+v", c.Inbounds)
	}
	// The settings are in the rules: the program, the torrent clients and
	// the site go directly.
	var programs []any
	var sites bool
	for _, r := range c.Routing.Rules {
		if p, ok := r["process"].([]any); ok && r["outboundTag"] == "direct" {
			programs = p
		}
		if d, ok := r["domain"].([]any); ok && len(d) == 1 && d[0] == "domain:bank.example" && r["outboundTag"] == "direct" {
			sites = true
		}
	}
	if len(programs) != 1+len(torrentClients) || programs[0] != "Telegram" || programs[1] != "qbittorrent" || !sites {
		t.Errorf("programs %v, site rule %v", programs, sites)
	}
	// Without torrents the clients go through the VPN like anything else.
	settings.TorrentsDirect = false
	if got := directPrograms(settings); len(got) != 1 {
		t.Errorf("direct programs %v", got)
	}
}

func TestShouldRunIsKept(t *testing.T) {
	path := filepath.Join(t.TempDir(), "runtime.json")
	newStore := func() *runtimeStore {
		return &runtimeStore{s: store.New(path, func() runtimeState { return runtimeState{} }, nil, func(string) {}), log: func(m string) { t.Error(m) }}
	}
	if newStore().ShouldRun() {
		t.Error("on by default")
	}
	newStore().SetShouldRun(true)
	if !newStore().ShouldRun() {
		t.Error("not kept")
	}
}
