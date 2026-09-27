// Command asc-profiles regenerates the Ad Hoc provisioning profiles for the
// app and its packet-tunnel extension through the App Store Connect API, so
// every registered iPhone is included. It runs on a Linux runner (no Mac,
// no fastlane) and only needs the standard library.
//
//	ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8 (contents of AuthKey_XXXX.p8)
//	go run . -out profiles \
//	  -profile 'com.klausms.vpn=Klaus VPN Ad Hoc' \
//	  -profile 'com.klausms.vpn.tunnel=Klaus VPN Tunnel Ad Hoc' \
//	  [-register 'UDID=Name']
package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type client struct {
	base   string
	keyID  string
	issuer string
	key    *ecdsa.PrivateKey
	http   *http.Client
}

// token builds the ES256 JWT the API wants (at most 20 minutes long).
func (c *client) token(now time.Time) (string, error) {
	enc := base64.RawURLEncoding
	h, _ := json.Marshal(map[string]string{"alg": "ES256", "kid": c.keyID, "typ": "JWT"})
	p, _ := json.Marshal(map[string]any{"iss": c.issuer, "iat": now.Unix(), "exp": now.Add(15 * time.Minute).Unix(), "aud": "appstoreconnect-v1"})
	signing := enc.EncodeToString(h) + "." + enc.EncodeToString(p)
	sum := sha256.Sum256([]byte(signing))
	r, s, err := ecdsa.Sign(rand.Reader, c.key, sum[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64) // JWS wants r||s, 32 bytes each, not DER
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return signing + "." + enc.EncodeToString(sig), nil
}

type resource struct {
	ID         string          `json:"id"`
	Type       string          `json:"type"`
	Attributes json.RawMessage `json:"attributes"`
}

func (c *client) do(method, path string, body any, out any) error {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, c.base+path, rd)
	if err != nil {
		return err
	}
	tok, err := c.token(time.Now())
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("%s %s: HTTP %d: %s", method, path, resp.StatusCode, strings.TrimSpace(string(data)))
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

func (c *client) list(path string) ([]resource, error) {
	var all []resource
	for next := path; next != ""; {
		var page struct {
			Data  []resource `json:"data"`
			Links struct {
				Next string `json:"next"`
			} `json:"links"`
		}
		if err := c.do("GET", next, nil, &page); err != nil {
			return nil, err
		}
		all = append(all, page.Data...)
		next = strings.TrimPrefix(page.Links.Next, c.base)
	}
	return all, nil
}

func attr(r resource, key string) string {
	var m map[string]any
	_ = json.Unmarshal(r.Attributes, &m)
	s, _ := m[key].(string)
	return s
}

func q(v url.Values) string { return "?" + v.Encode() }

type profileSpec struct{ bundleID, name string }

type multi []string

func (m *multi) String() string     { return strings.Join(*m, ",") }
func (m *multi) Set(v string) error { *m = append(*m, v); return nil }

func run() error {
	var profiles, register multi
	out := flag.String("out", "profiles", "directory for the .mobileprovision files")
	flag.Var(&profiles, "profile", "bundleID=profile name (repeat)")
	flag.Var(&register, "register", "UDID=device name to register first (repeat)")
	flag.Parse()

	block, _ := pem.Decode([]byte(os.Getenv("ASC_KEY_P8")))
	if block == nil {
		return errors.New("ASC_KEY_P8 is not a PEM private key")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return fmt.Errorf("ASC_KEY_P8: %w", err)
	}
	key, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return errors.New("ASC_KEY_P8 is not an EC key")
	}
	base := os.Getenv("ASC_API")
	if base == "" {
		base = "https://api.appstoreconnect.apple.com"
	}
	c := &client{base: base, keyID: os.Getenv("ASC_KEY_ID"), issuer: os.Getenv("ASC_ISSUER_ID"), key: key, http: &http.Client{Timeout: 60 * time.Second}}
	if c.keyID == "" || c.issuer == "" {
		return errors.New("ASC_KEY_ID and ASC_ISSUER_ID are required")
	}

	for _, r := range register {
		udid, name, _ := strings.Cut(r, "=")
		udid = strings.TrimSpace(udid)
		existing, err := c.list("/v1/devices" + q(url.Values{"filter[udid]": {udid}}))
		if err != nil {
			return err
		}
		if len(existing) > 0 {
			fmt.Printf("device %s already registered\n", udid)
			continue
		}
		body := map[string]any{"data": map[string]any{"type": "devices", "attributes": map[string]string{"name": name, "platform": "IOS", "udid": udid}}}
		if err := c.do("POST", "/v1/devices", body, nil); err != nil {
			return err
		}
		fmt.Printf("registered %s (%s)\n", name, udid)
	}

	certs, err := c.list("/v1/certificates" + q(url.Values{"filter[certificateType]": {"DISTRIBUTION"}}))
	if err != nil {
		return err
	}
	if serial := os.Getenv("ASC_CERT_SERIAL"); serial != "" {
		var keep []resource
		for _, x := range certs {
			if strings.EqualFold(attr(x, "serialNumber"), serial) {
				keep = append(keep, x)
			}
		}
		certs = keep
	}
	if len(certs) == 0 {
		return errors.New("no Apple Distribution certificate on the account")
	}
	sort.Slice(certs, func(i, j int) bool { return attr(certs[i], "expirationDate") > attr(certs[j], "expirationDate") })
	cert := certs[0]
	// The API writes dates like 2027-08-01T12:00:00.000+0000.
	if exp, err := time.Parse("2006-01-02T15:04:05.000-0700", attr(cert, "expirationDate")); err == nil && time.Until(exp) < 30*24*time.Hour {
		fmt.Printf("::warning::Apple Distribution certificate expires %s: installed apps stop opening then\n", exp.Format("2006-01-02"))
	}

	devices, err := c.list("/v1/devices" + q(url.Values{"filter[platform]": {"IOS"}, "filter[status]": {"ENABLED"}, "limit": {"200"}}))
	if err != nil {
		return err
	}
	if len(devices) == 0 {
		return errors.New("no enabled iOS devices registered: an Ad Hoc profile needs at least one")
	}
	var devRefs []map[string]string
	for _, d := range devices {
		devRefs = append(devRefs, map[string]string{"type": "devices", "id": d.ID})
	}
	fmt.Printf("%d registered iPhones/iPads\n", len(devices))

	if err := os.MkdirAll(*out, 0o755); err != nil {
		return err
	}
	for _, p := range profiles {
		bundle, name, ok := strings.Cut(p, "=")
		if !ok {
			return fmt.Errorf("bad -profile %q", p)
		}
		ids, err := c.list("/v1/bundleIds" + q(url.Values{"filter[identifier]": {bundle}}))
		if err != nil {
			return err
		}
		bundleRes := ""
		for _, b := range ids { // the filter also matches longer identifiers
			if attr(b, "identifier") == bundle {
				bundleRes = b.ID
			}
		}
		if bundleRes == "" {
			return fmt.Errorf("App ID %s is not registered (developer portal > Identifiers)", bundle)
		}
		old, err := c.list("/v1/profiles" + q(url.Values{"filter[name]": {name}}))
		if err != nil {
			return err
		}
		for _, o := range old {
			if attr(o, "name") == name {
				if err := c.do("DELETE", "/v1/profiles/"+o.ID, nil, nil); err != nil {
					return err
				}
			}
		}
		body := map[string]any{"data": map[string]any{
			"type":       "profiles",
			"attributes": map[string]string{"name": name, "profileType": "IOS_APP_ADHOC"},
			"relationships": map[string]any{
				"bundleId":     map[string]any{"data": map[string]string{"type": "bundleIds", "id": bundleRes}},
				"certificates": map[string]any{"data": []map[string]string{{"type": "certificates", "id": cert.ID}}},
				"devices":      map[string]any{"data": devRefs},
			},
		}}
		var created struct{ Data resource }
		if err := c.do("POST", "/v1/profiles", body, &created); err != nil {
			return err
		}
		content, err := base64.StdEncoding.DecodeString(attr(created.Data, "profileContent"))
		if err != nil || len(content) == 0 {
			return fmt.Errorf("profile %q came back without content", name)
		}
		file := filepath.Join(*out, attr(created.Data, "uuid")+".mobileprovision")
		if err := os.WriteFile(file, content, 0o644); err != nil {
			return err
		}
		fmt.Printf("%s -> %s (expires %s)\n", name, file, attr(created.Data, "expirationDate"))
	}
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "asc-profiles:", err)
		os.Exit(1)
	}
}
