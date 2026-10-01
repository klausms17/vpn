package netbind

import "testing"

func TestPick(t *testing.T) {
	const tun = 99
	wifi := Route{LUID: 1, Index: 12, Metric: 35 + 0, Up: true}
	cable := Route{LUID: 2, Index: 7, Metric: 25 + 0, Up: true}
	for _, c := range []struct {
		name   string
		routes []Route
		want   Choice
	}{
		{"nothing", nil, Choice{}},
		{"cable beats Wi-Fi by metric", []Route{wifi, cable}, Choice{V4: 7}},
		{"Wi-Fi when the cable is down", []Route{wifi, {LUID: 2, Index: 7, Metric: 25}}, Choice{V4: 12}},
		{"Wi-Fi with the lower metric wins", []Route{{LUID: 1, Index: 12, Metric: 10, Up: true}, cable}, Choice{V4: 12}},
		{"the tunnel never", []Route{{LUID: tun, Index: 40, Metric: 0, Up: true}, wifi}, Choice{V4: 12}},
		{"ties go to the lower index", []Route{{LUID: 3, Index: 9, Metric: 25, Up: true}, cable}, Choice{V4: 7}},
		{"families apart", []Route{wifi, {IPv6: true, LUID: 1, Index: 12, Metric: 40, Up: true}, cable}, Choice{V4: 7, V6: 12}},
		{"IPv6 only", []Route{{IPv6: true, LUID: 2, Index: 7, Metric: 5, Up: true}}, Choice{V6: 7}},
		{"no index", []Route{{LUID: 5, Metric: 1, Up: true}, wifi}, Choice{V4: 12}},
	} {
		if got := Pick(c.routes, tun); got != c.want {
			t.Errorf("%s: %+v, want %+v", c.name, got, c.want)
		}
	}
	// Before the tunnel exists its LUID is unknown (0): nothing is skipped.
	if got := Pick([]Route{{LUID: 0, Index: 3, Metric: 1, Up: true}}, 0); got != (Choice{V4: 3}) {
		t.Errorf("unknown tunnel: %+v", got)
	}
}
