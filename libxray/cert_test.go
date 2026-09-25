package libxray

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSelfSignedCertIsNotTrusted(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "example.com"},
		DNSNames:     []string{"example.com"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	if trustedBySystem([][]byte{der}, "example.com") {
		t.Fatal("a self-signed certificate must not count as trusted")
	}
	if trustedBySystem([][]byte{[]byte("garbage")}, "example.com") {
		t.Fatal("an unparsable certificate must not count as trusted")
	}
}

func TestFetchErrorsHideTheURL(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	addr := srv.URL
	srv.Close() // connection refused from now on
	secret := addr + "/sub/SECRET-TOKEN-123"
	_, err := Fetch(secret, "", 2000, "")
	if err == nil {
		t.Fatal("expected an error")
	}
	if strings.Contains(err.Error(), "SECRET-TOKEN-123") {
		t.Fatalf("error leaks the subscription token: %v", err)
	}
	_, err = Fetch("http://[::1", "", 2000, "")
	if err == nil || strings.Contains(err.Error(), "::1") {
		t.Fatalf("parse error should not echo the link: %v", err)
	}
}
