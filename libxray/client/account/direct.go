package account

import (
	"context"
	"encoding/json"

	"github.com/klausms17/vpn/libxray"
)

// Direct sends requests without a core, with the app's User-Agent: on
// Windows the service's own sockets stay outside its tunnel.
func Direct(userAgent string, timeoutMs int32) Do {
	return func(_ context.Context, r Request) (Response, error) {
		reply, err := libxray.Request(r.Method, r.URL, userAgent, r.Headers(), r.Body, timeoutMs, "")
		if err != nil {
			return Response{}, err
		}
		return Response{Status: int(reply.Status), Body: reply.Body}, nil
	}
}

// Headers are the request's extra headers as libxray takes them: the
// session token, when there is one.
func (r Request) Headers() string {
	if r.Token == "" {
		return ""
	}
	b, _ := json.Marshal(map[string]string{"Authorization": "Bearer " + r.Token})
	return string(b)
}
