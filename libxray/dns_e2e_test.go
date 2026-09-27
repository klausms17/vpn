package libxray

import (
	"encoding/binary"
	"encoding/json"
	"io"
	gonet "net"
	"strings"
	"sync"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
	xdns "github.com/xtls/xray-core/features/dns"
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

// udpDNSStub answers every A query over UDP with ip and a 1-second TTL,
// until stop is called.
func udpDNSStub(t *testing.T, ip [4]byte) (port int, stop func()) {
	pc, err := gonet.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var once sync.Once
	stop = func() { once.Do(func() { pc.Close() }) }
	t.Cleanup(stop)
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			// The question ends after the name's zero byte, type and class.
			end := 12
			for end < n && buf[end] != 0 {
				end += int(buf[end]) + 1
			}
			end += 5
			if n < 12 || end > n {
				continue
			}
			resp := append([]byte(nil), buf[:end]...)
			resp[2] |= 0x80 // response
			resp[3] = 0x80  // recursion available, no error
			binary.BigEndian.PutUint16(resp[4:], 1)
			binary.BigEndian.PutUint16(resp[8:], 0)
			binary.BigEndian.PutUint16(resp[10:], 0)
			if binary.BigEndian.Uint16(buf[end-4:]) == 1 {
				binary.BigEndian.PutUint16(resp[6:], 1)
				// The name as a pointer to the question, A, IN, TTL 1, 4 bytes.
				resp = append(resp, 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4)
				resp = append(resp, ip[:]...)
			} else {
				binary.BigEndian.PutUint16(resp[6:], 0)
			}
			pc.WriteTo(resp, from)
		}
	}()
	return pc.LocalAddr().(*gonet.UDPAddr).Port, stop
}

// Once a name's answer has expired, the DNS module returns it at once from
// the cache and refreshes it in the background, so a name already seen
// keeps resolving while the upstream (the server, for most names) is down.
func TestDNSServesStaleNamesWhenUpstreamIsDown(t *testing.T) {
	useTrimmedGeo(t)
	port, stop := udpDNSStub(t, [4]byte{203, 0, 113, 7})

	cfg, err := buildConfig(&BuildOptions{Outbounds: realityProfile(t).Outbounds, SocksPort: freePort(t)})
	if err != nil {
		t.Fatal(err)
	}
	// Only the stub as upstream, reached directly; the cache settings stay
	// as buildDNS sets them.
	cfg["dns"].(map[string]any)["servers"] = []any{map[string]any{"address": "127.0.0.1", "port": port}}
	routing := cfg["routing"].(map[string]any)
	routing["rules"] = append([]rule{{"inboundTag": []string{dnsModuleTag}, "outboundTag": DirectTag}}, routing["rules"].([]rule)...)
	b, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	inst, err := newInstance(string(b))
	if err != nil {
		t.Fatal(err)
	}
	if err := inst.Start(); err != nil {
		t.Fatal(err)
	}
	defer inst.Close()
	client := inst.GetFeature(xdns.ClientType()).(xdns.Client)
	lookup := func() string {
		t.Helper()
		ips, _, err := client.LookupIP("stale.example", xdns.IPOption{IPv4Enable: true})
		if err != nil || len(ips) != 1 {
			t.Fatalf("lookup: %v %v", ips, err)
		}
		return ips[0].String()
	}

	if got := lookup(); got != "203.0.113.7" {
		t.Fatalf("first answer %s", got)
	}
	stop()
	time.Sleep(1500 * time.Millisecond) // the 1-second TTL runs out
	start := time.Now()
	if got := lookup(); got != "203.0.113.7" {
		t.Fatalf("stale answer %s", got)
	}
	if d := time.Since(start); d > time.Second {
		t.Errorf("the stale answer took %v; it must come from the cache at once", d)
	}
}
