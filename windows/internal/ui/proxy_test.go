package ui

import (
	"net"
	"reflect"
	"strings"
	"testing"
)

func TestLocalAddr(t *testing.T) {
	for in, want := range map[string]string{
		"127.0.0.1:10809":         "127.0.0.1:10809",
		"LocalHost:1080":          "LocalHost:1080",
		"[::1]:7890":              "[::1]:7890",
		"127.0.0.2:8080":          "127.0.0.2:8080",
		"http://127.0.0.1:10809/": "127.0.0.1:10809",
	} {
		if got, ok := localAddr(in); !ok || got != want {
			t.Errorf("%q: %q, %t; want %q", in, got, ok, want)
		}
	}
	for _, in := range []string{"", "127.0.0.1", "localhost", "127.0.0.1:0", "127.0.0.1:70000", "192.168.1.10:3128", "proxy.corp.example:8080", "0.0.0.0:1080"} {
		if got, ok := localAddr(in); ok {
			t.Errorf("%q taken as %q", in, got)
		}
	}
}

func TestSystemProxyAddrs(t *testing.T) {
	for in, want := range map[string][]string{
		"127.0.0.1:10809": {"127.0.0.1:10809"},
		"http=127.0.0.1:10809;https=127.0.0.1:10809;socks=127.0.0.1:10808": {"127.0.0.1:10809", "127.0.0.1:10809", "127.0.0.1:10808"},
		// Not all of it on this computer: never one to remove.
		"http=127.0.0.1:10809;https=proxy.corp.example:8080": nil,
		"": nil,
	} {
		if got := systemProxyAddrs(in); !reflect.DeepEqual(got, want) {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestVariableAddr(t *testing.T) {
	for in, want := range map[string]string{
		"http://127.0.0.1:10809":              "127.0.0.1:10809",
		"socks5://user:secret@localhost:1080": "localhost:1080",
		" 127.0.0.1:10809 ":                   "127.0.0.1:10809",
	} {
		if got, ok := variableAddr(in); !ok || got != want {
			t.Errorf("%q: %q, %t; want %q", in, got, ok, want)
		}
	}
	if got, ok := variableAddr("http://proxy.corp.example:3128"); ok {
		t.Errorf("a proxy elsewhere taken as %q", got)
	}
}

func TestOnlyProxiesThatLeadNowhereAreDead(t *testing.T) {
	found := []proxySetting{
		{Where: systemProxy, Addrs: []string{"127.0.0.1:10809", "127.0.0.1:10808"}},
		{Where: "HTTPS_PROXY", Addrs: []string{"127.0.0.1:10809"}},
		// A debugging proxy that runs.
		{Where: "HTTP_PROXY", Addrs: []string{"127.0.0.1:8888"}},
		{Where: "ALL_PROXY", Addrs: []string{"127.0.0.1:10808"}, Machine: true},
	}
	running := func(addr string) bool { return addr == "127.0.0.1:8888" }
	got := dead(found, running)
	if len(got) != 3 || got[0].Where != systemProxy || got[1].Where != "HTTPS_PROXY" || got[2].Where != "ALL_PROXY" {
		t.Fatalf("dead %+v", got)
	}
	n := notice(got)
	if !reflect.DeepEqual(n.Addrs, []string{"127.0.0.1:10809", "127.0.0.1:10808"}) || !reflect.DeepEqual(n.Machine, []string{"ALL_PROXY"}) {
		t.Errorf("notice %+v", n)
	}
	// The program whose proxy it is runs: one port of it answers.
	if got := dead(found[:1], func(addr string) bool { return addr == "127.0.0.1:10808" }); len(got) != 0 {
		t.Errorf("a running program's proxy taken for dead: %+v", got)
	}
}

func TestListening(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	if !listening(addr) {
		t.Error("a listening port taken for dead")
	}
	l.Close()
	if listening(addr) {
		t.Error("a closed port taken for listening")
	}
}

// memoryProxies are a user's proxy settings in memory; remove keeps those
// for all users, as Windows' does.
type memoryProxies struct{ settings []proxySetting }

func (m *memoryProxies) read() []proxySetting { return m.settings }

func (m *memoryProxies) remove(gone []proxySetting) error {
	var left []proxySetting
	for _, s := range m.settings {
		if s.Machine || !containsWhere(gone, s.Where) {
			left = append(left, s)
		}
	}
	m.settings = left
	return nil
}

func containsWhere(settings []proxySetting, where string) bool {
	for _, s := range settings {
		if s.Where == where {
			return true
		}
	}
	return false
}

func TestTheWindowRemovesWhatItCan(t *testing.T) {
	m := &memoryProxies{settings: []proxySetting{
		{Where: systemProxy, Addrs: []string{"127.0.0.1:10809"}},
		{Where: "https_proxy", Addrs: []string{"127.0.0.1:10809"}},
		{Where: "ALL_PROXY", Addrs: []string{"127.0.0.1:10808"}, Machine: true},
	}}
	var logged []string
	b := &Bridge{proxy: proxyCheck{settings: m, listening: func(string) bool { return false }, log: func(s string) { logged = append(logged, s) }}}
	if n := b.DeadProxy(); len(n.Addrs) != 2 || !reflect.DeepEqual(n.Machine, []string{"ALL_PROXY"}) || !n.Fixable {
		t.Errorf("notice %+v", n)
	}
	text, err := b.RemoveDeadProxy()
	if err != nil || !strings.HasPrefix(text, "Прокси убран.") || !strings.Contains(text, "ALL_PROXY") {
		t.Errorf("%q, %v", text, err)
	}
	if n := b.DeadProxy(); !reflect.DeepEqual(n.Addrs, []string{"127.0.0.1:10808"}) || n.Fixable {
		t.Errorf("left %+v", n)
	}
	if len(logged) != 1 || logged[0] != "removed proxy settings that led nowhere: system, https_proxy, ALL_PROXY" {
		t.Errorf("logged %q", logged)
	}
}

func TestNoProxyNoNotice(t *testing.T) {
	b := &Bridge{proxy: proxyCheck{settings: &memoryProxies{}, listening: listening, log: func(string) { t.Error("logged") }}}
	if n := b.DeadProxy(); n.Addrs == nil || len(n.Addrs) != 0 || n.Machine != nil {
		t.Errorf("notice %+v", n)
	}
}
