package libxray

import (
	"encoding/binary"
	"io"
	gonet "net"
	"strings"
	"sync"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
)

// dnsQuery builds a minimal DNS query for name and qtype.
func dnsQuery(id uint16, name string, qtype uint16) []byte {
	b := []byte{byte(id >> 8), byte(id), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, label := range strings.Split(name, ".") {
		b = append(b, byte(len(label)))
		b = append(b, label...)
	}
	return append(b, 0, byte(qtype>>8), byte(qtype), 0, 1)
}

// tcpDNSStub answers every query over TCP with NXDOMAIN, which the core
// never produces itself, and records the query types it saw.
func tcpDNSStub(t *testing.T) (port int, seen func() []uint16) {
	l, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	var mu sync.Mutex
	var types []uint16
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				for {
					var n uint16
					if binary.Read(c, binary.BigEndian, &n) != nil {
						return
					}
					msg := make([]byte, n)
					if _, err := io.ReadFull(c, msg); err != nil || n < 17 {
						return
					}
					mu.Lock()
					types = append(types, binary.BigEndian.Uint16(msg[n-4:]))
					mu.Unlock()
					msg[2] |= 0x80                  // response
					msg[3] = msg[3]&0xf0 | 0x80 | 3 // recursion available, NXDOMAIN
					binary.Write(c, binary.BigEndian, n)
					c.Write(msg)
				}
			}()
		}
	}()
	return l.Addr().(*gonet.TCPAddr).Port, func() []uint16 {
		mu.Lock()
		defer mu.Unlock()
		return append([]uint16(nil), types...)
	}
}

// SRV, MX, TXT and NAPTR reach a real resolver over TCP through the proxy;
// HTTPS records and PTR still get the core's own empty answer at once.
func TestNonIPQueriesGoThroughTheProxy(t *testing.T) {
	useTrimmedGeo(t)
	s := startProbeServers(t)
	stub, seen := tcpDNSStub(t)
	old := plainDNS
	plainDNS.addr, plainDNS.port = "127.0.0.1", stub
	t.Cleanup(func() { plainDNS = old })

	cfg := buildOpts(t, BuildOptions{Outbounds: mustParse(t, s.realityLink(s.portA)).Outbounds, SocksPort: freePort(t)})
	ctrl := NewController()
	if err := ctrl.Start(cfg, 0); err != nil {
		t.Fatal(err)
	}
	defer ctrl.Stop()
	ctrl.mu.Lock()
	inst := ctrl.cur.inst
	ctrl.mu.Unlock()

	// An app's query to the tunnel's DNS address, as the routing sees it.
	ask := func(id, qtype uint16) (rcode byte, answers uint16) {
		t.Helper()
		ctx := session.ContextWithInbound(t0(), &session.Inbound{Tag: socksInboundTag})
		conn, err := core.Dial(ctx, inst, xnet.UDPDestination(xnet.ParseAddress(TunDNSv4), 53))
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := conn.Write(dnsQuery(id, "_xmpp-client._tcp.example.org", qtype)); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 1500)
		n, err := conn.Read(buf)
		if err != nil {
			t.Fatalf("type %d: no answer: %v", qtype, err)
		}
		if n < 12 || binary.BigEndian.Uint16(buf) != id || buf[2]&0x80 == 0 {
			t.Fatalf("type %d: bad answer % x", qtype, buf[:n])
		}
		return buf[3] & 0x0f, binary.BigEndian.Uint16(buf[6:])
	}

	ctrl.QueryTraffic() // reset the counters
	for i, qtype := range []uint16{33, 15, 16, 35} {
		if rcode, _ := ask(uint16(100+i), qtype); rcode != 3 {
			t.Errorf("type %d: rcode %d, want the resolver's NXDOMAIN", qtype, rcode)
		}
	}
	// Nothing else uses the proxy here: the queries went through it, not
	// straight from the core process.
	if tr := ctrl.QueryTraffic(); tr.ProxyUp == 0 || tr.DirectUp != 0 {
		t.Errorf("queries did not go through the proxy: %+v", tr)
	}
	for i, qtype := range []uint16{65, 12} {
		if rcode, answers := ask(uint16(200+i), qtype); rcode != 0 || answers != 0 {
			t.Errorf("type %d: rcode %d, %d answers, want an empty answer", qtype, rcode, answers)
		}
	}
	if got := seen(); len(got) != 4 {
		t.Errorf("resolver saw types %v, want only 33, 15, 16, 35", got)
	}
}
