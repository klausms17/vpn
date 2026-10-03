package ui

import "github.com/klausms17/vpn/windows/internal/ipc"

// trayLook is how the tray shows a snapshot.
type trayLook struct {
	// icon: "off", "connecting", "on" or "error".
	icon    string
	tooltip string
	// action is the menu's connect or disconnect item; enabled says
	// whether it can be used now.
	action  string
	connect bool
	enabled bool
}

// maxTooltip keeps the tooltip within Windows' 127 characters.
const maxTooltip = 100

func lookOf(s Snapshot) trayLook {
	look := trayLook{icon: "off", action: "Подключить", connect: true}
	var state string
	switch {
	case !s.Service:
		state = "служба не запущена"
		look.icon = "error"
	case s.Outdated:
		state = "перезапустите приложение"
		look.icon = "error"
	default:
		look.enabled = true
		switch s.Status.State {
		case ipc.Connecting:
			state, look.icon = "подключение…", "connecting"
			look.action, look.connect = "Отключить", false
		case ipc.Connected:
			state, look.icon = "подключено", "on"
			look.action, look.connect = "Отключить", false
		case ipc.Disconnecting:
			state, look.icon = "отключение…", "connecting"
			look.enabled = false
		case ipc.Failed:
			state, look.icon = "ошибка подключения", "error"
		default:
			state = "отключено"
		}
	}
	look.tooltip = "Kirov VPN — " + state
	if name := s.Status.ProfileName; name != "" && (look.icon == "on" || look.icon == "connecting") {
		look.tooltip += " (" + name + ")"
	}
	if r := []rune(look.tooltip); len(r) > maxTooltip {
		look.tooltip = string(r[:maxTooltip-1]) + "…"
	}
	return look
}
