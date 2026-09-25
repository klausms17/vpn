package libxray

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
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
		conn, err := quic.DialAddr(ctx, addr, tlsConf, &quic.Config{
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
		dialer := &net.Dialer{Timeout: timeout}
		conn, err := tls.DialWithDialer(dialer, "tcp", addr, tlsConf)
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
	sum := sha256.Sum256(certs[0])
	return hex.EncodeToString(sum[:]), nil
}
