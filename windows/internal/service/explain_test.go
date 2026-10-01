package service

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

// coreErr wraps cause as the core and libxray do.
func coreErr(cause error) error {
	return fmt.Errorf("start failed: %w", fmt.Errorf("app/proxyman/inbound: failed to start proxy > proxy/tun: unable to set ips > %w", cause))
}

func TestExplain(t *testing.T) {
	none := explainer{holder: func() string { return "" }, ipv6Off: func() bool { return false }}
	clash := explainer{holder: func() string { return "Meta" }, ipv6Off: func() bool { return true }}
	for _, c := range []struct {
		name  string
		x     explainer
		err   error
		want  string
		final bool
	}{
		{"another VPN holds the address", clash, coreErr(errObjectAlreadyExists), "Адрес VPN уже занят адаптером «Meta», скорее всего другим VPN.", true},
		{"the address was held for a moment", none, coreErr(errObjectAlreadyExists), "Адрес VPN был занят другим сетевым адаптером.", false},
		{"IPv6 switched off", clash, coreErr(errNotFound), "В Windows выключен протокол IPv6", true},
		{"the adapter not ready yet", none, coreErr(errNotFound), "Windows не успела подготовить сетевой адаптер Kirov VPN.", false},
		{"no WFP", none, errors.New("unable to block DNS and IPv6 outside the TUN (remove autoSystemWfpBlockLeak to run without) > FwpmEngineOpen0 failed"), "Не удалось включить защиту от утечек DNS", false},
		{"anything else", none, coreErr(errors.New("The system cannot find the file specified.")), "Не удалось запустить VPN (The system cannot find the file specified). Подключитесь ещё раз", false},
	} {
		got, final := c.x.explain(c.err)
		if !strings.HasPrefix(got, c.want) || final != c.final {
			t.Errorf("%s: %q, final %v", c.name, got, final)
		}
	}
}
