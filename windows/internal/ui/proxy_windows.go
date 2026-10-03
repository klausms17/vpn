package ui

import (
	"errors"
	"slices"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// proxyKeys are where Windows keeps a user's proxy settings; tests use
// keys of their own.
type proxyKeys struct {
	// internet (in HKCU) holds the system proxy, userEnv (in HKCU) the
	// user's variables and machineEnv (in HKLM) those for all users.
	internet, userEnv, machineEnv string
}

var windowsProxyKeys = proxyKeys{
	internet:   `Software\Microsoft\Windows\CurrentVersion\Internet Settings`,
	userEnv:    `Environment`,
	machineEnv: `SYSTEM\CurrentControlSet\Control\Session Manager\Environment`,
}

var (
	procInternetSetOption  = windows.NewLazySystemDLL("wininet.dll").NewProc("InternetSetOptionW")
	procSendMessageTimeout = windows.NewLazySystemDLL("user32.dll").NewProc("SendMessageTimeoutW")
)

const (
	internetOptionRefresh         = 37
	internetOptionSettingsChanged = 39
	hwndBroadcast                 = 0xffff
	wmSettingChange               = 0x001a
	smtoAbortIfHung               = 0x0002
)

// read returns the proxy settings that lead to this computer.
func (k proxyKeys) read() []proxySetting {
	var found []proxySetting
	if key, err := registry.OpenKey(registry.CURRENT_USER, k.internet, registry.QUERY_VALUE); err == nil {
		enabled, _, _ := key.GetIntegerValue("ProxyEnable")
		server, _, _ := key.GetStringValue("ProxyServer")
		key.Close()
		if addrs := systemProxyAddrs(server); enabled != 0 && len(addrs) > 0 {
			found = append(found, proxySetting{Where: systemProxy, Addrs: addrs})
		}
	}
	found = append(found, variables(registry.CURRENT_USER, k.userEnv, false)...)
	return append(found, variables(registry.LOCAL_MACHINE, k.machineEnv, true)...)
}

// variables returns the proxy variables of the key at path that lead to
// this computer.
func variables(root registry.Key, path string, machine bool) []proxySetting {
	key, err := registry.OpenKey(root, path, registry.QUERY_VALUE)
	if err != nil {
		return nil
	}
	defer key.Close()
	names, err := key.ReadValueNames(0)
	if err != nil {
		return nil
	}
	var found []proxySetting
	for _, name := range names {
		if !slices.ContainsFunc(proxyVariables, func(v string) bool { return strings.EqualFold(v, name) }) {
			continue
		}
		value, _, err := key.GetStringValue(name)
		if err != nil {
			continue
		}
		if addr, ok := variableAddr(value); ok {
			found = append(found, proxySetting{Where: name, Addrs: []string{addr}, Machine: machine})
		}
	}
	return found
}

// remove switches the system proxy off and deletes the user's variables
// among settings; those for all users stay.
func (k proxyKeys) remove(settings []proxySetting) error {
	var names []string
	system := false
	for _, s := range settings {
		switch {
		case s.Machine:
		case s.Where == systemProxy:
			system = true
		default:
			names = append(names, s.Where)
		}
	}
	if system {
		key, err := registry.OpenKey(registry.CURRENT_USER, k.internet, registry.SET_VALUE)
		if err != nil {
			return err
		}
		err = key.SetDWordValue("ProxyEnable", 0)
		key.Close()
		if err != nil {
			return err
		}
		// Running programs read the settings again.
		procInternetSetOption.Call(0, internetOptionSettingsChanged, 0, 0)
		procInternetSetOption.Call(0, internetOptionRefresh, 0, 0)
	}
	if len(names) > 0 {
		key, err := registry.OpenKey(registry.CURRENT_USER, k.userEnv, registry.SET_VALUE)
		if err != nil {
			return err
		}
		defer key.Close()
		for _, name := range names {
			if err := key.DeleteValue(name); err != nil && !errors.Is(err, registry.ErrNotExist) {
				return err
			}
		}
		// Programs started from now on get the variables without them.
		environment, _ := windows.UTF16PtrFromString("Environment")
		var result uintptr
		procSendMessageTimeout.Call(hwndBroadcast, wmSettingChange, 0, uintptr(unsafe.Pointer(environment)),
			smtoAbortIfHung, 5000, uintptr(unsafe.Pointer(&result)))
	}
	return nil
}
