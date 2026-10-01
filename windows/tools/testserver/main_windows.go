// Command testserver is a VLESS+REALITY server on 127.0.0.1 for CI's smoke
// test of the Windows app; it is never shipped. It writes its share link
// to -link and its access log to -log, and serves until it is stopped.
//
// It runs on the PC whose traffic the tunnel captures, so its own
// connections are bound to the physical network the way the service's
// are (netbind), or they would loop back into the tunnel. Its DNS is DoH,
// which the tunnel's DNS filter leaves alone.
package main

import (
	"crypto/ecdh"
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
	"time"

	"github.com/klausms17/vpn/windows/internal/netbind"
	core "github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

func main() {
	linkFile := flag.String("link", "", "where to write the share link")
	logFile := flag.String("log", "", "where Xray writes its access log")
	flag.Parse()
	if *linkFile == "" || *logFile == "" {
		log.Fatal("usage: testserver -link FILE -log FILE")
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
	      "target": %q, "serverNames": ["example.com"], "privateKey": %q, "shortIds": ["ab12"]}}
	  }],
	  "outbounds": [{"protocol": "freedom", "settings": {"domainStrategy": "UseIPv4"}}]
	}`, *logFile, port, id, target, b64(priv.Bytes()))
	inst, err := core.StartInstance("json", []byte(cfg))
	if err != nil {
		log.Fatal(err)
	}
	defer inst.Close()

	link := fmt.Sprintf("vless://%s@127.0.0.1:%d?type=tcp&security=reality&pbk=%s&fp=chrome&sni=example.com&sid=ab12&flow=xtls-rprx-vision#Smoke",
		id, port, b64(priv.PublicKey().Bytes()))
	if err := os.WriteFile(*linkFile, []byte(link+"\n"), 0o600); err != nil {
		log.Fatal(err)
	}
	log.Printf("serving on 127.0.0.1:%d", port)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt)
	<-stop
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
