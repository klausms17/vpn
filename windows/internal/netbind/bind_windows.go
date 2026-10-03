package netbind

import (
	"context"
	"crypto/md5"
	"errors"
	"fmt"
	"math/bits"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"github.com/xtls/xray-core/transport/internet"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// IP_UNICAST_IF and IPV6_UNICAST_IF (ws2ipdef.h).
const (
	ipUnicastIf   = 31
	ipv6UnicastIf = 31
)

// settle lets a burst of network changes end before the choice is made.
const settle = 300 * time.Millisecond

// ErrNoNetwork refuses a connection while no physical network is known:
// sent anyway, it would go into the tunnel and loop back into the service.
var ErrNoNetwork = errors.New("нет подключения к сети")

// Binder binds the process's sockets while the tunnel runs; see the
// package comment. Create one per process, before anything connects: it
// registers a dialer controller with Xray, which stays for good, and sets
// net.DefaultResolver.
type Binder struct {
	tunnel   windows.GUID
	log      func(string)
	changed  func(prev, next Choice)
	loopback uint32

	active atomic.Bool
	// settled: the core runs, so its adapter and routes are in place.
	settled atomic.Bool
	state   atomic.Pointer[state]
	kick    chan struct{}
	// refreshMu keeps an older reading of the routes from replacing a newer one.
	refreshMu sync.Mutex
}

type state struct {
	Choice
	// tunIndex is the tunnel's interface index, 0 while it has none.
	tunIndex uint32
}

// New watches the network for the tunnel adapter named adapter (Xray
// derives its GUID from the name). changed is called, on a goroutine of
// its own, when the chosen interfaces change while the binder is active.
func New(adapter string, log func(string), changed func(prev, next Choice)) (*Binder, error) {
	id := md5.Sum([]byte(adapter))
	b := &Binder{
		tunnel:   *(*windows.GUID)(unsafe.Pointer(&id[0])),
		log:      log,
		changed:  changed,
		loopback: loopbackIndex(),
		kick:     make(chan struct{}, 1),
	}
	b.state.Store(&state{})
	// winipcfg runs each callback on a goroutine of its own. They are never
	// unregistered: doing that from inside a callback deadlocks.
	poke := func() {
		select {
		case b.kick <- struct{}{}:
		default:
		}
	}
	if _, err := winipcfg.RegisterRouteChangeCallback(func(winipcfg.MibNotificationType, *winipcfg.MibIPforwardRow2) { poke() }); err != nil {
		return nil, err
	}
	if _, err := winipcfg.RegisterInterfaceChangeCallback(func(winipcfg.MibNotificationType, *winipcfg.MibIPInterfaceRow) { poke() }); err != nil {
		return nil, err
	}
	if _, err := winipcfg.RegisterUnicastAddressChangeCallback(func(winipcfg.MibNotificationType, *winipcfg.MibUnicastIPAddressRow) { poke() }); err != nil {
		return nil, err
	}
	go b.watch()
	b.refresh()

	if err := internet.RegisterDialerController(b.control); err != nil {
		return nil, err
	}
	// Go's own resolver, for good: Windows' would ask the DNS Client
	// service, whose queries the tunnel's DNS filter sends into the tunnel.
	// Set once, before anything resolves, so no lookup races with it.
	dialer := &net.Dialer{Timeout: 5 * time.Second, Control: b.control}
	net.DefaultResolver.PreferGo = true
	net.DefaultResolver.Dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		// The tunnel's own DNS server leads back into the tunnel.
		if internet.IsSkippedDNSServer(address) {
			return nil, errors.New("skipped the tunnel's DNS server")
		}
		return dialer.DialContext(ctx, network, address)
	}
	return b, nil
}

// Activate starts binding every socket: call it before the core starts.
func (b *Binder) Activate() {
	b.refresh()
	b.settled.Store(false)
	b.active.Store(true)
}

// Settle, once the core runs, leaves alone the sockets that Windows would
// not send into the tunnel anyway. Until then the routes are being set up,
// and every socket is bound.
func (b *Binder) Settle() {
	b.refresh()
	b.settled.Store(true)
}

// Deactivate stops binding: call it after the core stopped.
func (b *Binder) Deactivate() { b.active.Store(false) }

// Current returns the chosen interfaces.
func (b *Binder) Current() Choice { return b.state.Load().Choice }

func (b *Binder) watch() {
	for range b.kick {
		time.Sleep(settle)
		select {
		case <-b.kick:
		default:
		}
		b.refresh()
	}
}

func (b *Binder) refresh() {
	b.refreshMu.Lock()
	defer b.refreshMu.Unlock()
	routes, tunLUID, tunIndex, err := b.read()
	if err != nil {
		b.log(fmt.Sprintf("network: routes cannot be read (%T)", err))
		return
	}
	next := &state{Choice: Pick(routes, tunLUID), tunIndex: tunIndex}
	prev := b.state.Swap(next)
	if prev != nil && prev.Choice == next.Choice {
		return
	}
	b.log(fmt.Sprintf("network: IPv4 via interface %d, IPv6 via %d", next.V4, next.V6))
	if prev != nil && b.active.Load() && b.changed != nil {
		go b.changed(prev.Choice, next.Choice)
	}
}

// read lists the default routes, and the tunnel's LUID and index if its
// adapter exists.
func (b *Binder) read() (routes []Route, tunLUID uint64, tunIndex uint32, err error) {
	if luid, err := winipcfg.LUIDFromGUID(&b.tunnel); err == nil {
		tunLUID = uint64(luid)
		if row, err := luid.Interface(); err == nil {
			tunIndex = row.InterfaceIndex
		}
	}
	rows, err := winipcfg.GetIPForwardTable2(windows.AF_UNSPEC)
	if err != nil {
		return nil, 0, 0, err
	}
	for i := range rows {
		r := &rows[i]
		if r.DestinationPrefix.PrefixLength != 0 {
			continue
		}
		family := r.DestinationPrefix.RawPrefix.Family
		ifRow, err := r.InterfaceLUID.Interface()
		if err != nil || ifRow.Type == winipcfg.IfTypeSoftwareLoopback {
			continue
		}
		ipIf, err := r.InterfaceLUID.IPInterface(family)
		if err != nil {
			continue
		}
		routes = append(routes, Route{
			IPv6:   family == windows.AF_INET6,
			LUID:   uint64(r.InterfaceLUID),
			Index:  r.InterfaceIndex,
			Metric: r.Metric + ipIf.Metric,
			Up:     ifRow.OperStatus == winipcfg.IfOperStatusUp,
		})
	}
	return routes, tunLUID, tunIndex, nil
}

// control binds one socket of the process; address is where it connects
// to, or for a listening socket its local address.
func (b *Binder) control(network, address string, c syscall.RawConn) error {
	if !b.active.Load() {
		return nil
	}
	dst, perr := netip.ParseAddrPort(address)
	switch {
	case perr == nil && dst.Addr().IsLoopback():
		return nil
	case perr != nil && strings.HasPrefix(strings.ToLower(address), "localhost:"):
		return nil
	}
	st := b.state.Load()
	// Left alone when Windows sends it elsewhere than into the tunnel: the
	// LAN, the network of another VPN, a second network card.
	if perr == nil && !dst.Addr().IsUnspecified() && b.settled.Load() && !intoTunnel(dst.Addr(), st.tunIndex) {
		return nil
	}
	var berr error
	if err := c.Control(func(fd uintptr) { berr = b.bind(windows.Handle(fd), network, st.Choice) }); err != nil {
		return err
	}
	return berr
}

// intoTunnel reports whether Windows would send traffic to dst into the
// tunnel. While the tunnel has no interface it counts as yes.
func intoTunnel(dst netip.Addr, tunIndex uint32) bool {
	if tunIndex == 0 {
		return true
	}
	dst = dst.Unmap()
	var sa windows.Sockaddr
	if dst.Is4() {
		sa = &windows.SockaddrInet4{Addr: dst.As4()}
	} else {
		sa = &windows.SockaddrInet6{Addr: dst.As16()}
	}
	var index uint32
	if err := windows.GetBestInterfaceEx(sa, &index); err != nil {
		return true
	}
	return index == tunIndex
}

// bind sets the socket's outgoing interface. Without a physical network
// it binds the socket to loopback, so whatever ignores the error (Xray's
// dialer only logs it) still cannot send into the tunnel.
func (b *Binder) bind(fd windows.Handle, network string, c Choice) error {
	v4 := c.V4
	if v4 == 0 {
		v4 = b.loopback
	}
	// The option takes an IPv4 interface index in network byte order.
	err4 := windows.SetsockoptInt(fd, windows.IPPROTO_IP, ipUnicastIf, int(bits.ReverseBytes32(v4)))
	switch network {
	case "tcp4", "udp4":
		if c.V4 == 0 {
			return ErrNoNetwork
		}
		return err4
	case "tcp6", "udp6":
		// Go's IPv6 sockets are dual-stack: the IPv4 option above covers
		// their IPv4 traffic.
		var err6 error
		if c.V6 != 0 {
			err6 = windows.SetsockoptInt(fd, windows.IPPROTO_IPV6, ipv6UnicastIf, int(c.V6))
		}
		if c.V4 == 0 && c.V6 == 0 {
			return ErrNoNetwork
		}
		return errors.Join(err4, err6)
	}
	return nil
}

// loopbackIndex is the index of the loopback interface, 1 on every
// Windows seen so far.
func loopbackIndex() uint32 {
	ifs, err := net.Interfaces()
	if err == nil {
		for _, i := range ifs {
			if i.Flags&net.FlagLoopback != 0 {
				return uint32(i.Index)
			}
		}
	}
	return 1
}
