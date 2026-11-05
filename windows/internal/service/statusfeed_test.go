package service

import (
	"reflect"
	"testing"

	"github.com/klausms17/vpn/windows/internal/ipc"
)

func TestStatusFeed(t *testing.T) {
	var sent []ipc.Status
	f := newStatusFeed(func(s ipc.Status) { sent = append(sent, s) })
	if f.Status().State != ipc.Disconnected {
		t.Fatalf("%+v", f.Status())
	}
	connected := ipc.Status{State: ipc.Connected, ProfileName: "DE", ConnectedSince: 1}
	f.fromEngine(connected)
	f.warn("Трафик перехватывает другой VPN")
	f.warn("Трафик перехватывает другой VPN")
	f.fromEngine(ipc.Status{State: ipc.Connected, ProfileName: "DE", Message: "Применяем изменения…", ConnectedSince: 1})
	f.fromEngine(ipc.Status{State: ipc.Disconnecting, ProfileName: "DE"})
	f.warn("")
	f.fromEngine(connected)
	warned := connected
	warned.Warning = "Трафик перехватывает другой VPN"
	want := []ipc.Status{
		connected,
		warned,
		{State: ipc.Connected, ProfileName: "DE", Message: "Применяем изменения…", Warning: "Трафик перехватывает другой VPN", ConnectedSince: 1},
		{State: ipc.Disconnecting, ProfileName: "DE"},
		connected,
	}
	if !reflect.DeepEqual(sent, want) {
		t.Fatalf("sent %+v", sent)
	}
	if f.Status() != connected {
		t.Errorf("shown %+v", f.Status())
	}
}
