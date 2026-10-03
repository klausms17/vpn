package ui

import (
	"errors"
	"net"
	"net/netip"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"
)

// A proxy setting another VPN program leaves on the PC (Happ, v2rayN and
// the like serve one on a local port while they run) sends the programs
// that use it to a port where nothing listens once that program is off:
// they cannot go online, while Kirov VPN, a tunnel for every program,
// needs no proxy at all. The window finds such settings while the tunnel
// is up and offers to remove them.

// systemProxy names the proxy of Windows' internet settings; the others
// are environment variables.
const systemProxy = "system"

// proxyVariables are the variables programs take a proxy from.
var proxyVariables = []string{"HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"}

// proxySetting is a proxy the user's programs are told to use.
type proxySetting struct {
	// Where is systemProxy or the variable's name as set.
	Where string
	// Addrs are the proxy's host:port addresses on this computer; a setting
	// with any other address is never one to remove.
	Addrs []string
	// Machine is a variable set for all users, which only an administrator
	// can remove.
	Machine bool
}

// DeadProxy is what the window says about the proxy settings that lead
// nowhere.
type DeadProxy struct {
	// Addrs are where they lead, as "127.0.0.1:10809"; none when all is well.
	Addrs []string `json:"addrs"`
	// Machine names the variables among them set for all users, which the
	// window cannot remove; Fixable tells that it can remove the others.
	Machine []string `json:"machine,omitempty"`
	Fixable bool     `json:"fixable"`
}

// listenTimeout bounds the check for a program listening on a proxy's port.
const listenTimeout = 300 * time.Millisecond

// listening tells whether a program accepts connections on addr.
func listening(addr string) bool {
	c, err := net.DialTimeout("tcp", addr, listenTimeout)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

// dead keeps the settings all of whose addresses lead nowhere.
func dead(found []proxySetting, listening func(string) bool) []proxySetting {
	var out []proxySetting
	for _, s := range found {
		if len(s.Addrs) > 0 && !slices.ContainsFunc(s.Addrs, listening) {
			out = append(out, s)
		}
	}
	return out
}

// notice is what the window shows about dead settings.
func notice(dead []proxySetting) DeadProxy {
	n := DeadProxy{Addrs: []string{}}
	for _, s := range dead {
		for _, a := range s.Addrs {
			if !slices.Contains(n.Addrs, a) {
				n.Addrs = append(n.Addrs, a)
			}
		}
		if s.Machine {
			n.Machine = append(n.Machine, s.Where)
		} else {
			n.Fixable = true
		}
	}
	return n
}

// systemProxyAddrs reads Windows' proxy setting, "host:port" or
// "http=host:port;https=host:port;socks=host:port". It returns nil when any
// part of it is not on this computer.
func systemProxyAddrs(server string) []string {
	var addrs []string
	for part := range strings.SplitSeq(server, ";") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if _, value, ok := strings.Cut(part, "="); ok {
			part = value
		}
		addr, ok := localAddr(part)
		if !ok {
			return nil
		}
		addrs = append(addrs, addr)
	}
	return addrs
}

// variableAddr reads a proxy variable, "http://127.0.0.1:10809",
// "socks5://user:password@localhost:1080" or "127.0.0.1:10809", and
// returns its address when it is on this computer.
func variableAddr(value string) (string, bool) {
	value = strings.TrimSpace(value)
	if strings.Contains(value, "://") {
		u, err := url.Parse(value)
		if err != nil {
			return "", false
		}
		value = u.Host
	}
	return localAddr(value)
}

// localAddr returns host:port, without a scheme, when its host is this
// computer and the port a real one.
func localAddr(hostPort string) (string, bool) {
	if i := strings.Index(hostPort, "://"); i >= 0 {
		hostPort = hostPort[i+3:]
	}
	host, port, err := net.SplitHostPort(strings.TrimSuffix(strings.TrimSpace(hostPort), "/"))
	if err != nil {
		return "", false
	}
	if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
		return "", false
	}
	if !strings.EqualFold(host, "localhost") {
		ip, err := netip.ParseAddr(host)
		if err != nil || !ip.IsLoopback() {
			return "", false
		}
	}
	return net.JoinHostPort(host, port), true
}

// proxySettings reads and removes a user's proxy settings.
type proxySettings interface {
	read() []proxySetting
	remove([]proxySetting) error
}

// proxyCheck finds the proxy settings that lead nowhere, and removes them.
type proxyCheck struct {
	settings  proxySettings
	listening func(string) bool
	log       func(string)
}

func (c proxyCheck) find() []proxySetting { return dead(c.settings.read(), c.listening) }

// DeadProxy tells which of the user's proxy settings lead to a port on
// this computer where nothing listens, as another VPN program leaves them.
func (b *Bridge) DeadProxy() DeadProxy { return notice(b.proxy.find()) }

// RemoveDeadProxy removes those settings, but for variables set for all
// users, and says what is left to do.
func (b *Bridge) RemoveDeadProxy() (string, error) {
	found := b.proxy.find()
	if err := b.proxy.settings.remove(found); err != nil {
		return "", errors.New("Не удалось убрать прокси: " + err.Error())
	}
	b.proxy.log("removed proxy settings that led nowhere: " + wheres(found))
	text := "Прокси убран. Перезапустите программы, которые не выходили в интернет."
	if left := notice(found).Machine; len(left) > 0 {
		text += " Переменную " + strings.Join(left, ", ") + " для всех пользователей уберите сами: «Изменение системных переменных среды», нужны права администратора."
	}
	return text, nil
}

// wheres lists where settings are, for the log.
func wheres(settings []proxySetting) string {
	names := make([]string, len(settings))
	for i, s := range settings {
		names[i] = s.Where
	}
	return strings.Join(names, ", ")
}
