package libxray

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	gonet "net"
	"net/http"
	neturl "net/url"
	"os"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/klausms17/vpn/libxray/internal/fsx"
	core "github.com/xtls/xray-core/core"
)

// DefaultTestURL answers 204 quickly from almost everywhere.
const DefaultTestURL = "https://www.gstatic.com/generate_204"

// maxFetchBytes caps subscription downloads so a broken or hostile panel
// cannot exhaust memory: Fetch also runs inside the VPN process. Large geo
// databases go through DownloadFile, which streams to disk instead.
const maxFetchBytes = 8 << 20

func proxyTransport(inst *core.Instance) *http.Transport {
	return proxyTransportVia(inst, ProxyTag)
}

// proxyTransportVia dials every connection through the outbound tagged tag.
func proxyTransportVia(inst *core.Instance, tag string) *http.Transport {
	return &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (gonet.Conn, error) {
			return dialVia(ctx, inst, tag, network, addr)
		},
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          2,
		IdleConnTimeout:       30 * time.Second,
	}
}

func measureDelay(parent context.Context, inst *core.Instance, url string, timeout time.Duration) (int64, error) {
	return measureDelayVia(parent, inst, ProxyTag, url, timeout)
}

// measureDelayVia gives up when parent ends (the instance is being stopped).
func measureDelayVia(parent context.Context, inst *core.Instance, tag string, url string, timeout time.Duration) (int64, error) {
	if url == "" {
		url = DefaultTestURL
	}
	tr := proxyTransportVia(inst, tag)
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: timeout}

	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()

	// The first request pays for the proxy handshake; the second one shows
	// the latency a user actually feels on an open connection. Report the
	// best successful attempt.
	best := int64(-1)
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		start := time.Now()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return -1, err
		}
		resp, err := client.Do(req)
		if err != nil {
			lastErr = err
			if ctx.Err() != nil {
				break
			}
			continue
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
			lastErr = fmt.Errorf("unexpected status %s", resp.Status)
			continue
		}
		elapsed := time.Since(start).Milliseconds()
		if best < 0 || elapsed < best {
			best = elapsed
		}
	}
	if best < 0 {
		if lastErr == nil {
			lastErr = errors.New("delay test failed")
		}
		return -1, lastErr
	}
	return best, nil
}

// FetchResult is the body of a downloaded document plus the headers that
// subscription panels use to describe themselves. Text headers sent as
// "base64:..." (Remnawave does that for non-ASCII values) are decoded.
type FetchResult struct {
	Body []byte
	// Raw "subscription-userinfo" header: upload=..; download=..; total=..; expire=..
	UserInfo string
	// "profile-title" header.
	ProfileTitle string
	// Raw "profile-update-interval" header, in hours.
	UpdateInterval string
	// "support-url" header: where the user can ask the owner for help.
	SupportUrl string
	// "profile-web-page-url" header: the subscription's web page.
	WebPageUrl string
	// "announce" header: a message from the owner.
	Announce string
	// "klaus-report-url" header (our panel only): where the app reports a
	// server that failover had to leave.
	ReportUrl string
	// "klaus-app-url" header (our panel only): the version.json of the
	// latest published app build.
	AppUrl string
	// Remnawave device limit ("x-hwid-*: true" headers). HwidActive: the
	// limit applies to this user. HwidLimit: the request was refused because
	// of it, HwidMaxDevices: no free device slot, HwidNotSupported: the
	// device id was missing or invalid. When refused, the body only holds
	// placeholders (see SubscriptionResult.Notices) or nothing.
	HwidActive       bool
	HwidLimit        bool
	HwidMaxDevices   bool
	HwidNotSupported bool
}

// Fetch downloads url and returns the body. When proxyConfigJSON is not
// empty the request goes through a temporary Xray instance built from it
// (it must contain an outbound tagged "proxy"); otherwise it goes direct.
// Unlike Android's Java stack it also works for plain-http subscription
// links, which many self-hosted panels still use.
func Fetch(url string, userAgent string, timeoutMs int32, proxyConfigJSON string) (*FetchResult, error) {
	return FetchWithHeaders(url, userAgent, "", timeoutMs, proxyConfigJSON)
}

// FetchWithHeaders is Fetch plus extra request headers, given as a JSON
// object ({"X-Hwid":"...", ...}; "" for none). Names must be HTTP tokens;
// Host, Content-Length, Transfer-Encoding, Connection and User-Agent are
// ignored, and control characters are removed from values.
//
// With proxyConfigJSON the temporary instance takes over Xray's
// process-wide state (see Controller.restoreGlobals): in the process that
// runs the tunnel use Controller.FetchThroughTunnel instead.
func FetchWithHeaders(url, userAgent, headersJSON string, timeoutMs int32, proxyConfigJSON string) (result *FetchResult, err error) {
	defer recoverInto(&err)

	extra, err := parseHeaders(headersJSON)
	if err != nil {
		return nil, err
	}
	var tr *http.Transport
	if proxyConfigJSON != "" {
		inst, err := newInstance(proxyConfigJSON)
		if err != nil {
			return nil, err
		}
		defer inst.Close()
		if err := inst.Start(); err != nil {
			return nil, fmt.Errorf("start failed: %w", err)
		}
		tr = proxyTransport(inst)
	} else {
		tr = directTransport()
	}
	defer tr.CloseIdleConnections()
	return doFetch(context.Background(), tr, url, userAgent, extra, timeoutDuration(timeoutMs))
}

func doFetch(ctx context.Context, tr http.RoundTripper, url, userAgent string, extra http.Header, timeout time.Duration) (*FetchResult, error) {
	client := &http.Client{Transport: tr, Timeout: timeout}
	resp, err := get(ctx, client, url, userAgent, extra)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, httpError(resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxFetchBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxFetchBytes {
		return nil, errors.New("response is too large")
	}
	h := resp.Header
	flag := func(name string) bool { return strings.EqualFold(strings.TrimSpace(h.Get(name)), "true") }
	return &FetchResult{
		Body:             data,
		UserInfo:         strings.TrimSpace(h.Get("Subscription-Userinfo")),
		ProfileTitle:     headerText(h.Get("Profile-Title")),
		UpdateInterval:   strings.TrimSpace(h.Get("Profile-Update-Interval")),
		SupportUrl:       headerText(h.Get("Support-Url")),
		WebPageUrl:       headerText(h.Get("Profile-Web-Page-Url")),
		Announce:         headerText(h.Get("Announce")),
		ReportUrl:        headerText(h.Get("Klaus-Report-Url")),
		AppUrl:           headerText(h.Get("Klaus-App-Url")),
		HwidActive:       flag("X-Hwid-Active"),
		HwidLimit:        flag("X-Hwid-Limit"),
		HwidMaxDevices:   flag("X-Hwid-Max-Devices-Reached"),
		HwidNotSupported: flag("X-Hwid-Not-Supported"),
	}, nil
}

// headerText decodes a panel header value: "base64:<text>" (how Remnawave
// sends anything that is not plain ASCII) or plain text. A value that does
// not decode to UTF-8 text is dropped.
func headerText(v string) string {
	v = strings.TrimSpace(v)
	if rest, ok := strings.CutPrefix(v, "base64:"); ok {
		b, err := decodeBase64Loose(rest)
		if err != nil || !utf8.Valid(b) {
			return ""
		}
		v = string(b)
	}
	return strings.TrimSpace(v)
}

// parseHeaders turns the JSON object of extra request headers into an
// http.Header. Errors never quote values: they carry the device id.
func parseHeaders(headersJSON string) (http.Header, error) {
	if strings.TrimSpace(headersJSON) == "" {
		return nil, nil
	}
	var m map[string]string
	if err := json.Unmarshal([]byte(headersJSON), &m); err != nil {
		return nil, errors.New("bad request headers: need a JSON object of strings")
	}
	h := http.Header{}
	for name, value := range m {
		if !isToken(name) {
			return nil, fmt.Errorf("bad request header name %q", name)
		}
		switch http.CanonicalHeaderKey(name) {
		case "Host", "Content-Length", "Transfer-Encoding", "Connection", "User-Agent":
			continue
		}
		value = strings.TrimSpace(strings.Map(func(r rune) rune {
			if unicode.IsControl(r) {
				return -1
			}
			return r
		}, value))
		if value != "" {
			h.Set(name, value)
		}
	}
	return h, nil
}

// isToken reports whether s is an HTTP token (RFC 9110), the only valid
// form of a header name.
func isToken(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9':
		case strings.IndexByte("!#$%&'*+-.^_`|~", c) >= 0:
		default:
			return false
		}
	}
	return true
}

// DownloadFile streams url into dst (replaced atomically on success). It is
// used for the large geo databases, which must not be held in memory.
// proxyConfigJSON works like in Fetch.
func DownloadFile(url string, dst string, userAgent string, timeoutMs int32, proxyConfigJSON string) (err error) {
	defer recoverInto(&err)

	var tr *http.Transport
	if proxyConfigJSON != "" {
		inst, err := newInstance(proxyConfigJSON)
		if err != nil {
			return err
		}
		defer inst.Close()
		if err := inst.Start(); err != nil {
			return fmt.Errorf("start failed: %w", err)
		}
		tr = proxyTransport(inst)
	} else {
		tr = directTransport()
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{Transport: tr, Timeout: timeoutDuration(timeoutMs)}
	resp, err := get(context.Background(), client, url, userAgent, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return httpError(resp.StatusCode)
	}

	tmp := dst + ".part"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	written, copyErr := io.Copy(out, io.LimitReader(resp.Body, 512<<20))
	syncErr := out.Sync()
	closeErr := out.Close()
	if err := errors.Join(copyErr, syncErr, closeErr); err != nil {
		os.Remove(tmp)
		return err
	}
	if resp.ContentLength > 0 && written != resp.ContentLength {
		os.Remove(tmp)
		return fmt.Errorf("incomplete download: %d of %d bytes", written, resp.ContentLength)
	}
	return fsx.Replace(tmp, dst)
}

// get performs a GET whose errors never contain the URL: subscription links
// carry a secret token, and error texts end up in logs and on screen. extra
// headers (may be nil) are added to the request, and never to geo downloads.
func get(ctx context.Context, client *http.Client, rawURL string, userAgent string, extra http.Header) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, errors.New("invalid link")
	}
	for name, values := range extra {
		req.Header[name] = values
	}
	if userAgent != "" {
		req.Header.Set("User-Agent", userAgent)
	}
	resp, err := client.Do(req)
	if err != nil {
		var ue *neturl.Error
		if errors.As(err, &ue) {
			return nil, fmt.Errorf("%s: %w", req.URL.Hostname(), ue.Err)
		}
		return nil, err
	}
	return resp, nil
}

// httpError names an HTTP status by its code and Go's text for it, never
// the server's own reason phrase, which may be megabytes long.
func httpError(code int) error {
	return fmt.Errorf("HTTP %s", strings.TrimSpace(fmt.Sprintf("%d %s", code, http.StatusText(code))))
}
