package libxray

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	neturl "net/url"
	"time"
)

// HTTPReply is the answer to Request, whatever its status.
type HTTPReply struct {
	Status int32
	Body   []byte
}

// maxReplyBytes bounds an answer to Request: it is for small APIs.
const maxReplyBytes = 64 << 10

// Request sends one call to a small HTTP API, such as the accounts service
// (docs/accounts/PLAN.md), and returns the answer whatever its status: such
// an API says in the body why it refused. method is GET or POST; body is
// sent as JSON (empty for none: gomobile may hand over an empty array for
// none); headersJSON is as for FetchWithHeaders and
// may carry Authorization. Without proxyConfigJSON it goes directly, with
// it through a temporary instance (see FetchWithHeaders). Errors name the
// host only: never the URL, a header or the body.
func Request(method, url, userAgent, headersJSON string, body []byte, timeoutMs int32, proxyConfigJSON string) (reply *HTTPReply, err error) {
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
	return doRequest(context.Background(), tr, method, url, userAgent, extra, body, timeoutDuration(timeoutMs))
}

// RequestThroughTunnel is Request through the running tunnel's server, for
// the process that runs the tunnel.
func (c *Controller) RequestThroughTunnel(method, url, userAgent, headersJSON string, body []byte, timeoutMs int32) (reply *HTTPReply, err error) {
	defer recoverInto(&err)

	extra, err := parseHeaders(headersJSON)
	if err != nil {
		return nil, err
	}
	inst, ctx, done, err := c.use()
	if err != nil {
		return nil, err
	}
	defer done()
	tr := proxyTransport(inst)
	defer tr.CloseIdleConnections()
	return doRequest(ctx, tr, method, url, userAgent, extra, body, timeoutDuration(timeoutMs))
}

func doRequest(ctx context.Context, tr http.RoundTripper, method, rawURL, userAgent string, extra http.Header, body []byte,
	timeout time.Duration) (*HTTPReply, error) {
	if method != http.MethodGet && method != http.MethodPost {
		return nil, errors.New("only GET and POST")
	}
	var reader io.Reader
	if len(body) > 0 {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, rawURL, reader)
	if err != nil {
		return nil, errors.New("invalid link")
	}
	for name, values := range extra {
		req.Header[name] = values
	}
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}
	if userAgent != "" {
		req.Header.Set("User-Agent", userAgent)
	}
	resp, err := (&http.Client{Transport: tr, Timeout: timeout}).Do(req)
	if err != nil {
		var ue *neturl.Error
		if errors.As(err, &ue) {
			return nil, fmt.Errorf("%s: %w", req.URL.Hostname(), ue.Err)
		}
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxReplyBytes+1))
	if err != nil {
		return nil, fmt.Errorf("%s: %w", req.URL.Hostname(), err)
	}
	if len(data) > maxReplyBytes {
		return nil, errors.New("response is too large")
	}
	return &HTTPReply{Status: int32(resp.StatusCode), Body: data}, nil
}
