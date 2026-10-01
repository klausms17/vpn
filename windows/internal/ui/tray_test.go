package ui

import (
	"strings"
	"testing"

	"github.com/klausms17/vpn/windows/internal/ipc"
)

func TestTrayLook(t *testing.T) {
	up := func(st ipc.Status) Snapshot { return Snapshot{Service: true, Status: st} }
	for _, c := range []struct {
		snap                  Snapshot
		icon, tooltip, action string
		connect, enabled      bool
	}{
		{Snapshot{}, "error", "Kirov VPN — служба не запущена", "Подключить", true, false},
		{Snapshot{Service: true, Outdated: true}, "error", "Kirov VPN — перезапустите приложение", "Подключить", true, false},
		{up(ipc.Status{State: ipc.Disconnected}), "off", "Kirov VPN — отключено", "Подключить", true, true},
		{up(ipc.Status{State: ipc.Connecting, ProfileName: "DE"}), "connecting", "Kirov VPN — подключение… (DE)", "Отключить", false, true},
		{up(ipc.Status{State: ipc.Connected, ProfileName: "Германия"}), "on", "Kirov VPN — подключено (Германия)", "Отключить", false, true},
		{up(ipc.Status{State: ipc.Disconnecting, ProfileName: "DE"}), "connecting", "Kirov VPN — отключение… (DE)", "Подключить", true, false},
		{up(ipc.Status{State: ipc.Failed, Message: "Ядро не запустилось"}), "error", "Kirov VPN — ошибка подключения", "Подключить", true, true},
	} {
		got := lookOf(c.snap)
		if got.icon != c.icon || got.tooltip != c.tooltip || got.action != c.action || got.connect != c.connect || got.enabled != c.enabled {
			t.Errorf("%+v: %+v", c.snap, got)
		}
	}
	long := lookOf(up(ipc.Status{State: ipc.Connected, ProfileName: strings.Repeat("я", 200)}))
	if n := len([]rune(long.tooltip)); n != maxTooltip {
		t.Errorf("tooltip of %d characters", n)
	}
}
