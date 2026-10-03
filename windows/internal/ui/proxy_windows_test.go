package ui

import (
	"os"
	"strconv"
	"testing"

	"golang.org/x/sys/windows/registry"
)

// The settings are read from and removed in keys of the test's own, under
// the test user's HKCU, never the real ones.
func TestProxyKeysReadAndRemove(t *testing.T) {
	base := `Software\KirovVPN-test-` + strconv.Itoa(os.Getpid())
	keys := proxyKeys{internet: base + `\Internet`, userEnv: base + `\Environment`, machineEnv: base + `\Machine`}
	t.Cleanup(func() {
		registry.DeleteKey(registry.CURRENT_USER, keys.internet)
		registry.DeleteKey(registry.CURRENT_USER, keys.userEnv)
		registry.DeleteKey(registry.CURRENT_USER, base)
	})
	internet, _, err := registry.CreateKey(registry.CURRENT_USER, keys.internet, registry.ALL_ACCESS)
	if err != nil {
		t.Fatal(err)
	}
	defer internet.Close()
	env, _, err := registry.CreateKey(registry.CURRENT_USER, keys.userEnv, registry.ALL_ACCESS)
	if err != nil {
		t.Fatal(err)
	}
	defer env.Close()
	for _, err := range []error{
		internet.SetDWordValue("ProxyEnable", 1),
		internet.SetStringValue("ProxyServer", "http=127.0.0.1:1;https=127.0.0.1:1"),
		env.SetStringValue("HTTPS_PROXY", "http://127.0.0.1:1"),
		env.SetStringValue("http_proxy", "http://proxy.corp.example:3128"),
		env.SetExpandStringValue("Path", `C:\Tools`),
	} {
		if err != nil {
			t.Fatal(err)
		}
	}

	found := keys.read()
	if len(found) != 2 || found[0].Where != systemProxy || len(found[0].Addrs) != 2 || found[1].Where != "HTTPS_PROXY" || found[1].Machine {
		t.Fatalf("found %+v", found)
	}
	if err := keys.remove(found); err != nil {
		t.Fatal(err)
	}
	if enabled, _, _ := internet.GetIntegerValue("ProxyEnable"); enabled != 0 {
		t.Error("the system proxy is still on")
	}
	if _, _, err := env.GetStringValue("HTTPS_PROXY"); err == nil {
		t.Error("HTTPS_PROXY is still there")
	}
	// What leads elsewhere, and other variables, stay.
	for _, name := range []string{"http_proxy", "Path"} {
		if _, _, err := env.GetStringValue(name); err != nil {
			t.Errorf("%s: %v", name, err)
		}
	}
	if left := keys.read(); len(left) != 0 {
		t.Errorf("left %+v", left)
	}
}
