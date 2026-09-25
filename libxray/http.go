package libxray

import (
	"context"
	"errors"
	"fmt"
	"io"
	gonet "net"
	"net/http"
	neturl "net/url"
	"os"
	"time"

	core "github.com/xtls/xray-core/core"
)

// DefaultTestURL answers 204 quickly from almost everywhere.
const DefaultTestURL = "https://www.gstatic.com/generate_204"

// maxFetchBytes caps subscription / geo downloads so a broken server can't
// exhaust memory.
const maxFetchBytes = 64 << 20

func proxyTransport(inst *core.Instance) *http.Transport {
	return &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (gonet.Conn, error) {
			return dialThroughProxy(ctx, inst, network, addr)
		},
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          2,
		IdleConnTimeout:       30 * time.Second,
	}
}

func measureDelay(inst *core.Instance, url string, timeout time.Duration) (int64, error) {
	if url == "" {
		url = DefaultTestURL
	}
	tr := proxyTransport(inst)
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: timeout}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
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
// subscription panels use to describe themselves.
type FetchResult struct {
	Body []byte
	// Raw "subscription-userinfo" header: upload=..; download=..; total=..; expire=..
	UserInfo string
	// Raw "profile-title" header (may be "base64:...").
	ProfileTitle string
	// Raw "profile-update-interval" header, in hours.
	UpdateInterval string
}

// Fetch downloads url and returns the body. When proxyConfigJSON is not
// empty the request goes through a temporary Xray instance built from it
// (it must contain an outbound tagged "proxy"); otherwise it goes direct.
// Unlike Android's Java stack it also works for plain-http subscription
// links, which many self-hosted panels still use.
func Fetch(url string, userAgent string, timeoutMs int32, proxyConfigJSON string) (result *FetchResult, err error) {
	defer recoverInto(&err)

	timeout := timeoutDuration(timeoutMs)
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
		tr = http.DefaultTransport.(*http.Transport).Clone()
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{Transport: tr, Timeout: timeout}
	resp, err := get(client, url, userAgent)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("HTTP %s", resp.Status)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxFetchBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxFetchBytes {
		return nil, errors.New("response is too large")
	}
	return &FetchResult{
		Body:           data,
		UserInfo:       resp.Header.Get("Subscription-Userinfo"),
		ProfileTitle:   resp.Header.Get("Profile-Title"),
		UpdateInterval: resp.Header.Get("Profile-Update-Interval"),
	}, nil
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
		tr = http.DefaultTransport.(*http.Transport).Clone()
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{Transport: tr, Timeout: timeoutDuration(timeoutMs)}
	resp, err := get(client, url, userAgent)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("HTTP %s", resp.Status)
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
	return os.Rename(tmp, dst)
}

// get performs a GET whose errors never contain the URL: subscription links
// carry a secret token, and error texts end up in logs and on screen.
func get(client *http.Client, rawURL string, userAgent string) (*http.Response, error) {
	req, err := http.NewRequest(http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, errors.New("invalid link")
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
