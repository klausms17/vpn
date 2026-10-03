package libxray

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"net"
	"strconv"
	"time"

	quic "github.com/apernet/quic-go"
)

// FetchCertSha256 connects to host:port and returns the SHA-256 (hex) of the
// server's leaf certificate. Xray removed "allowInsecure"; share links that
// still ask for it (self-signed servers) are made to work by pinning the
// certificate the first time they are imported ("pinnedPeerCertSha256").
// Set useQuic for QUIC based transports (Hysteria2).
//
// When the certificate is in fact valid for serverName under the system
// roots, it returns "" instead: normal verification then applies, which
// keeps working after the certificate is renewed (a pinned leaf would not).
func FetchCertSha256(host string, port int32, serverName string, useQuic bool, timeoutMs int32) (hash string, err error) {
	defer recoverInto(&err)

	if host == "" {
		return "", errors.New("empty host")
	}
	if port <= 0 {
		port = 443
	}
	if serverName == "" {
		serverName = host
	}
	timeout := timeoutDuration(timeoutMs)
	addr := net.JoinHostPort(host, strconv.Itoa(int(port)))
	tlsConf := &tls.Config{
		ServerName:         serverName,
		InsecureSkipVerify: true, // we only read the certificate to pin it
		MinVersion:         tls.VersionTLS12,
	}

	var certs [][]byte
	if useQuic {
		tlsConf.NextProtos = []string{"h3"}
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		udpAddr, err := net.ResolveUDPAddr("udp", addr)
		if err != nil {
			return "", err
		}
		pconn, err := directListenConfig().ListenPacket(ctx, "udp", ":0")
		if err != nil {
			return "", err
		}
		defer pconn.Close()
		conn, err := quic.Dial(ctx, pconn, udpAddr, tlsConf, &quic.Config{
			HandshakeIdleTimeout: timeout,
			MaxIdleTimeout:       timeout,
		})
		if err != nil {
			return "", err
		}
		defer conn.CloseWithError(0, "")
		for _, c := range conn.ConnectionState().TLS.PeerCertificates {
			certs = append(certs, c.Raw)
		}
	} else {
		conn, err := tls.DialWithDialer(directDialer(timeout), "tcp", addr, tlsConf)
		if err != nil {
			return "", err
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(timeout))
		for _, c := range conn.ConnectionState().PeerCertificates {
			certs = append(certs, c.Raw)
		}
	}
	if len(certs) == 0 {
		return "", errors.New("server sent no certificate")
	}
	if trustedBySystem(certs, serverName) {
		return "", nil
	}
	sum := sha256.Sum256(certs[0])
	return hex.EncodeToString(sum[:]), nil
}

func trustedBySystem(raw [][]byte, serverName string) bool {
	parsed := make([]*x509.Certificate, 0, len(raw))
	for _, der := range raw {
		c, err := x509.ParseCertificate(der)
		if err != nil {
			return false
		}
		parsed = append(parsed, c)
	}
	intermediates := x509.NewCertPool()
	for _, c := range parsed[1:] {
		intermediates.AddCert(c)
	}
	_, err := parsed[0].Verify(x509.VerifyOptions{DNSName: serverName, Intermediates: intermediates})
	return err == nil
}
