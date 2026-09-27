// Command e2e is the Go half of the local Remnawave test (run-local.sh).
//
//	e2e serve -target 127.0.0.1:44080 -http 11.11.11.11:18080 -token T
//	    runs the site the REALITY inbound imitates (TLS 1.3 with a
//	    realistic certificate chain) and a plain web page answering T.
//	e2e check -sub URL -resolve HOST:IP -cacert FILE -hwid H -url URL -expect T
//	    fetches the subscription like Kirov VPN does, parses it with the
//	    app's own core (libxray) and fetches URL through every server in it.
//	    -save FILE keeps the subscription; -body FILE uses a kept one instead
//	    of downloading; -expect-fail succeeds only if no server lets the
//	    request through (e.g. after the user was disabled).
package main

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/klausms17/vpn/libxray"
)

// The app's User-Agent keeps its former name on purpose (the panel matches it).
const userAgent = "KlausVPN/1.0.99 (Android)"

func main() {
	if len(os.Args) < 2 {
		fail("usage: e2e serve|check ...")
	}
	switch os.Args[1] {
	case "serve":
		serve(os.Args[2:])
	case "check":
		check(os.Args[2:])
	default:
		fail("unknown mode " + os.Args[1])
	}
}

func fail(msg string) {
	fmt.Fprintln(os.Stderr, "FAIL:", msg)
	os.Exit(1)
}

func serve(args []string) {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	target := fs.String("target", "127.0.0.1:44080", "REALITY target (TLS) address")
	web := fs.String("http", "11.11.11.11:18080", "test page address")
	token := fs.String("token", "klaus-e2e-ok", "test page body")
	_ = fs.Parse(args)

	tlsLn, err := tls.Listen("tcp", *target, &tls.Config{
		Certificates: []tls.Certificate{bigChain()},
		MinVersion:   tls.VersionTLS13,
		NextProtos:   []string{"h2", "http/1.1"},
	})
	if err != nil {
		fail(err.Error())
	}
	go func() { _ = http.Serve(tlsLn, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})) }()

	webLn, err := net.Listen("tcp", *web)
	if err != nil {
		fail(err.Error())
	}
	go func() {
		_ = http.Serve(webLn, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			fmt.Fprintf(w, "%s from %s\n", *token, r.RemoteAddr)
		}))
	}()
	fmt.Printf("serving REALITY target on %s and the test page on %s\n", *target, *web)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
}

// bigChain imitates a real website: REALITY needs a certificate chain of
// several KB (as in libxray's e2e tests).
func bigChain() tls.Certificate {
	now := time.Now()
	caKey, _ := rsa.GenerateKey(rand.Reader, 4096)
	caTmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Test Root CA", Organization: []string{"Example Trust Services"}},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(48 * time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	caDER, _ := x509.CreateCertificate(rand.Reader, caTmpl, caTmpl, &caKey.PublicKey, caKey)
	ca, _ := x509.ParseCertificate(caDER)
	imKey, _ := rsa.GenerateKey(rand.Reader, 4096)
	imTmpl := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "Test Intermediate CA R1", Organization: []string{"Example Trust Services"}},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(48 * time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	imDER, _ := x509.CreateCertificate(rand.Reader, imTmpl, ca, &imKey.PublicKey, caKey)
	im, _ := x509.ParseCertificate(imDER)
	leafKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	leafTmpl := &x509.Certificate{SerialNumber: big.NewInt(3), Subject: pkix.Name{CommonName: "www.example.com"},
		DNSNames:  []string{"example.com", "www.example.com", "cdn.example.com", "static.example.com", "api.example.com"},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(24 * time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	leafDER, _ := x509.CreateCertificate(rand.Reader, leafTmpl, im, &leafKey.PublicKey, imKey)
	return tls.Certificate{Certificate: [][]byte{leafDER, imDER, caDER}, PrivateKey: leafKey}
}

func check(args []string) {
	fs := flag.NewFlagSet("check", flag.ExitOnError)
	sub := fs.String("sub", "", "subscription link")
	resolve := fs.String("resolve", "", "HOST:IP to use instead of DNS for the subscription host")
	caFile := fs.String("cacert", "", "extra CA (Caddy's internal root)")
	hwid := fs.String("hwid", "0123456789abcdef0123456789abcdef", "X-Hwid to send")
	target := fs.String("url", "http://11.11.11.11:18080/", "page to fetch through each server")
	expect := fs.String("expect", "klaus-e2e-ok", "text the page must contain")
	save := fs.String("save", "", "write the downloaded subscription here")
	saved := fs.String("body", "", "use this saved subscription instead of downloading")
	expectFail := fs.Bool("expect-fail", false, "pass only if the page cannot be fetched")
	_ = fs.Parse(args)

	var body []byte
	if *saved != "" {
		b, err := os.ReadFile(*saved)
		if err != nil {
			fail(err.Error())
		}
		body = b
		fmt.Printf("subscription: saved copy %s, %d bytes\n", *saved, len(body))
	} else {
		if *sub == "" {
			fail("-sub is required")
		}
		body = download(*sub, *resolve, *caFile, *hwid)
		if *save != "" {
			if err := os.WriteFile(*save, body, 0o600); err != nil {
				fail(err.Error())
			}
		}
	}

	// Parsed by the app's core.
	parsed, err := libxray.ParseSubscription(body)
	if err != nil {
		fail("ParseSubscription: " + err.Error())
	}
	var res struct {
		Profiles []struct {
			Name, Protocol, Address, Network, Security string
			Port                                       int
			Outbounds                                  json.RawMessage
		} `json:"profiles"`
	}
	if err := json.Unmarshal([]byte(parsed), &res); err != nil {
		fail(err.Error())
	}
	if len(res.Profiles) == 0 {
		fail("no servers in the subscription")
	}

	// A real request through every server of the subscription.
	for _, p := range res.Profiles {
		fmt.Printf("server %q: %s %s:%d %s/%s\n", p.Name, p.Protocol, p.Address, p.Port, p.Network, p.Security)
		cfg, err := libxray.BuildProxyOnlyConfig(string(p.Outbounds))
		if err != nil {
			fail("BuildProxyOnlyConfig: " + err.Error())
		}
		r, err := libxray.Fetch(*target, userAgent, 10000, cfg)
		switch {
		case err != nil && *expectFail:
			fmt.Printf("  refused as expected: %v\n", err)
			continue
		case err != nil:
			fail("fetch through the server: " + err.Error())
		case *expectFail:
			fail("the server still lets the request through")
		}
		got := strings.TrimSpace(string(r.Body))
		fmt.Printf("  through it %s answered: %s\n", *target, got)
		if !strings.Contains(got, *expect) {
			fail("unexpected answer")
		}
	}
	if *expectFail {
		fmt.Println("OK: no server lets the request through")
	} else {
		fmt.Println("OK: the subscription works end to end")
	}
}

// download fetches the subscription the way the app does (same User-Agent
// and device headers), bypassing any proxy of this machine.
func download(sub, resolve, caFile, hwid string) []byte {
	pool, _ := x509.SystemCertPool()
	if pool == nil {
		pool = x509.NewCertPool()
	}
	if caFile != "" {
		pem, err := os.ReadFile(caFile)
		if err != nil || !pool.AppendCertsFromPEM(pem) {
			fail("cannot load " + caFile)
		}
	}
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	tr := &http.Transport{
		Proxy:           nil,
		TLSClientConfig: &tls.Config{RootCAs: pool},
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			if host, ip, ok := strings.Cut(resolve, ":"); ok {
				if h, port, err := net.SplitHostPort(addr); err == nil && h == host {
					addr = net.JoinHostPort(ip, port)
				}
			}
			return dialer.DialContext(ctx, network, addr)
		},
	}
	req, _ := http.NewRequest(http.MethodGet, sub, nil)
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("X-Hwid", hwid)
	req.Header.Set("X-Device-Os", "Android")
	req.Header.Set("X-Ver-Os", "15")
	req.Header.Set("X-Device-Model", "Google Pixel 8")
	resp, err := (&http.Client{Transport: tr, Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		fail("subscription: " + err.Error())
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	fmt.Printf("subscription: HTTP %d, %d bytes\n", resp.StatusCode, len(body))
	for _, h := range []string{"Profile-Title", "Profile-Update-Interval", "Subscription-Userinfo", "Support-Url", "X-Hwid-Active"} {
		if v := resp.Header.Get(h); v != "" {
			fmt.Printf("  %s: %s\n", h, v)
		}
	}
	if resp.StatusCode != http.StatusOK {
		fail("subscription status")
	}
	return body
}
