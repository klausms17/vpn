// Command testserver is a VLESS+REALITY server on 127.0.0.1 for CI's smoke
// test of the Windows app; it is never shipped. It writes its share link
// to -link and its access log to -log, and serves until it is stopped.
// With -sub it also serves a subscription to that server, as a panel
// would (see serveSubscription).
//
// It runs on the PC whose traffic the tunnel captures, so its own
// connections are bound to the physical network the way the service's
// are (netbind), or they would loop back into the tunnel. Its DNS is DoH,
// which the tunnel's DNS filter leaves alone.
//
// The app sends most sites by address, so the server reads the site's name
// from the connection and sends the test site, gstatic.com, through an
// outbound of that name: its access log then names the site either way.
package main

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"flag"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/klausms17/vpn/windows/internal/netbind"
	core "github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

func main() {
	linkFile := flag.String("link", "", "where to write the share link")
	logFile := flag.String("log", "", "where Xray writes its access log")
	subDir := flag.String("sub", "", "where to write the subscription's address, certificate and requests")
	flag.Parse()
	if *linkFile == "" || *logFile == "" {
		log.Fatal("usage: testserver -link FILE -log FILE [-sub DIR]")
	}

	binder, err := netbind.New("Kirov VPN", func(m string) { log.Print(m) }, nil)
	if err != nil {
		log.Fatal(err)
	}
	binder.Activate()
	binder.Settle()

	// REALITY borrows the TLS 1.3 handshake of a site: a local one here.
	target := tlsTarget()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		log.Fatal(err)
	}
	b64 := base64.RawURLEncoding.EncodeToString
	port := freePort()
	id := "8f1c5f8e-3d0a-4b5c-9e2f-6a7b8c9d0e1f"
	cfg := fmt.Sprintf(`{
	  "log": {"loglevel": "warning", "access": %q},
	  "dns": {"servers": ["https://1.1.1.1/dns-query"], "queryStrategy": "UseIPv4"},
	  "inbounds": [{
	    "listen": "127.0.0.1", "port": %d, "protocol": "vless",
	    "settings": {"clients": [{"id": %q, "flow": "xtls-rprx-vision"}], "decryption": "none"},
	    "streamSettings": {"network": "raw", "security": "reality", "realitySettings": {
	      "target": %q, "serverNames": ["example.com"], "privateKey": %q, "shortIds": ["ab12"]}},
	    "sniffing": {"enabled": true, "destOverride": ["tls", "http"], "routeOnly": true}
	  }],
	  "outbounds": [
	    {"protocol": "freedom", "streamSettings": {"sockopt": {"domainStrategy": "UseIPv4"}}},
	    {"tag": "gstatic", "protocol": "freedom", "streamSettings": {"sockopt": {"domainStrategy": "UseIPv4"}}}
	  ],
	  "routing": {"rules": [{"domain": ["domain:gstatic.com"], "outboundTag": "gstatic"}]}
	}`, *logFile, port, id, target, b64(priv.Bytes()))
	inst, err := core.StartInstance("json", []byte(cfg))
	if err != nil {
		log.Fatal(err)
	}
	defer inst.Close()

	link := fmt.Sprintf("vless://%s@127.0.0.1:%d?type=tcp&security=reality&pbk=%s&fp=chrome&sni=example.com&sid=ab12&flow=xtls-rprx-vision#Smoke",
		id, port, b64(priv.PublicKey().Bytes()))
	if *subDir != "" {
		serveSubscription(*subDir, strings.TrimSuffix(link, "#Smoke")+"#Smoke-sub")
	}
	// Written last: the smoke test starts once it is there.
	if err := os.WriteFile(*linkFile, []byte(link+"\n"), 0o600); err != nil {
		log.Fatal(err)
	}
	log.Printf("serving on 127.0.0.1:%d", port)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt)
	<-stop
}

// serveSubscription serves a subscription with link on 127.0.0.1 over
// HTTPS, with the headers Remnawave sends. Into dir it writes sub.url, its
// address, sub.cer, the certificate the test makes Windows trust, and
// sub.log, a line per download with what the app said of itself.
func serveSubscription(dir, link string) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		log.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "Kirov VPN smoke test"},
		// Windows checks the name of an address among the DNS names.
		DNSNames:    []string{"127.0.0.1"},
		IPAddresses: []net.IP{net.IPv4(127, 0, 0, 1)},
		NotBefore:   time.Now().Add(-time.Hour),
		NotAfter:    time.Now().Add(48 * time.Hour),
		KeyUsage:    x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		log.Fatal(err)
	}
	l, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}},
	})
	if err != nil {
		log.Fatal(err)
	}
	var mu sync.Mutex
	body := base64.StdEncoding.EncodeToString([]byte(link + "\n"))
	title := base64.StdEncoding.EncodeToString([]byte("Smoke subscription"))
	go http.Serve(l, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := r.Header
		mu.Lock()
		f, err := os.OpenFile(filepath.Join(dir, "sub.log"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
		if err == nil {
			fmt.Fprintf(f, "ua=%s hwid=%d os=%s ver=%s model=%s\n", r.UserAgent(), len(h.Get("X-Hwid")),
				h.Get("X-Device-Os"), h.Get("X-Ver-Os"), h.Get("X-Device-Model"))
			f.Close()
		}
		mu.Unlock()
		w.Header().Set("Profile-Title", "base64:"+title)
		w.Header().Set("Subscription-Userinfo", fmt.Sprintf("upload=0; download=1048576; total=1073741824; expire=%d", time.Now().Add(30*24*time.Hour).Unix()))
		w.Header().Set("Profile-Update-Interval", "1")
		fmt.Fprint(w, body)
	}))
	write := func(name string, data []byte) {
		if err := os.WriteFile(filepath.Join(dir, name), data, 0o600); err != nil {
			log.Fatal(err)
		}
	}
	write("sub.cer", der)
	write("sub.url", []byte(fmt.Sprintf("https://%s/sub/smoke\n", l.Addr())))
}

// tlsTarget serves TLS 1.3 on 127.0.0.1 and returns its address.
func tlsTarget() string {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		log.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "example.com"},
		DNSNames:     []string{"example.com"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(48 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		log.Fatal(err)
	}
	l, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}},
		NextProtos:   []string{"h2", "http/1.1"},
		MinVersion:   tls.VersionTLS13,
	})
	if err != nil {
		log.Fatal(err)
	}
	go http.Serve(l, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	return l.Addr().String()
}

func freePort() int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}
