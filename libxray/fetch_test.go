package libxray

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHeaderText(t *testing.T) {
	ru := "Подписка друзей 🇩🇪"
	cases := map[string]string{
		"":                  "",
		"  plain title  ":   "plain title",
		"https://t.me/help": "https://t.me/help",
		"base64:" + base64.StdEncoding.EncodeToString([]byte(ru)):             ru,
		"base64:" + base64.RawStdEncoding.EncodeToString([]byte(ru)):          ru, // no padding
		"base64:" + base64.RawURLEncoding.EncodeToString([]byte(ru)):          ru, // url-safe
		" base64:" + base64.StdEncoding.EncodeToString([]byte("  x  ")) + " ": "x",
		"base64:!!!not base64": "",
		"base64:" + base64.StdEncoding.EncodeToString([]byte{0xff, 0xfe}): "", // not UTF-8
		"Base64:abc": "Base64:abc",
	}
	for in, want := range cases {
		if got := headerText(in); got != want {
			t.Errorf("headerText(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestParseHeaders(t *testing.T) {
	h, err := parseHeaders("")
	if err != nil || h != nil {
		t.Fatalf("empty: %v %v", h, err)
	}
	h, err = parseHeaders(`{"x-hwid":"0123456789abcdef","X-Device-Model":"Pixel\r\n 7\u0000 Pro\t","Host":"evil.example","User-Agent":"Mozilla","Connection":"close","Content-Length":"1","Transfer-Encoding":"chunked","X-Empty":"\u0007"}`)
	if err != nil {
		t.Fatal(err)
	}
	expect(t, h.Get("X-Hwid"), "0123456789abcdef")
	expect(t, h.Get("X-Device-Model"), "Pixel 7 Pro")
	for _, name := range []string{"Host", "User-Agent", "Connection", "Content-Length", "Transfer-Encoding", "X-Empty"} {
		if _, ok := h[name]; ok {
			t.Errorf("%s must be dropped", name)
		}
	}

	secret := "SECRET-HWID-42"
	for _, bad := range []string{
		`{"X-Hwid":"` + secret + `"`,          // broken JSON
		`["` + secret + `"]`,                  // not an object
		`{"X-Hwid":["` + secret + `"]}`,       // not a string
		`{"X Hwid":"` + secret + `"}`,         // space in the name
		`{"X-Hwid:":"` + secret + `"}`,        // colon in the name
		`{"":"` + secret + `"}`,               // empty name
		`{"X-Имя":"` + secret + `"}`,          // non-ASCII name
		`{"X-Hwid\r\nEvil":"` + secret + `"}`, // header injection
	} {
		_, err := parseHeaders(bad)
		if err == nil {
			t.Errorf("%s: expected an error", bad)
			continue
		}
		if strings.Contains(err.Error(), secret) {
			t.Errorf("%s: error leaks the value: %v", bad, err)
		}
	}
}

func TestFetchWithHeaders(t *testing.T) {
	ru := "Лимит устройств исчерпан"
	var got http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		got.Set("Host", r.Host)
		h := w.Header()
		h.Set("Subscription-Userinfo", "upload=0; download=10; total=0; expire=0")
		h.Set("Profile-Title", "base64:"+base64.StdEncoding.EncodeToString([]byte("Друзья")))
		h.Set("Profile-Update-Interval", "12")
		h.Set("Support-Url", "https://t.me/owner")
		h.Set("Profile-Web-Page-Url", "base64:"+base64.StdEncoding.EncodeToString([]byte("https://sub.example.com/abc")))
		h.Set("Announce", "base64:"+base64.StdEncoding.EncodeToString([]byte(ru)))
		h.Set("X-Hwid-Active", "true")
		h.Set("X-Hwid-Limit", "TRUE")
		h.Set("X-Hwid-Max-Devices-Reached", "true")
		h.Set("X-Hwid-Not-Supported", "false")
		w.Write([]byte("body"))
	}))
	defer srv.Close()

	res, err := FetchWithHeaders(srv.URL+"/sub/token", "KlausVPN/1.2 (Android)",
		`{"X-Hwid":"0123456789abcdef0123456789abcdef","X-Device-Os":"Android","X-Ver-Os":"15","X-Device-Model":"Google Pixel 9","User-Agent":"Mozilla/5.0","Host":"evil.example"}`,
		5000, "")
	if err != nil {
		t.Fatal(err)
	}
	expect(t, got.Get("X-Hwid"), "0123456789abcdef0123456789abcdef")
	expect(t, got.Get("X-Device-Os"), "Android")
	expect(t, got.Get("X-Ver-Os"), "15")
	expect(t, got.Get("X-Device-Model"), "Google Pixel 9")
	expect(t, got.Get("User-Agent"), "KlausVPN/1.2 (Android)")
	expect(t, got.Get("Host"), strings.TrimPrefix(srv.URL, "http://"))
	if a := got.Get("Accept"); strings.Contains(a, "text/html") {
		t.Errorf("Accept %q makes panels serve the web page", a)
	}

	expect(t, string(res.Body), "body")
	expect(t, res.UserInfo, "upload=0; download=10; total=0; expire=0")
	expect(t, res.ProfileTitle, "Друзья")
	expect(t, res.UpdateInterval, "12")
	expect(t, res.SupportUrl, "https://t.me/owner")
	expect(t, res.WebPageUrl, "https://sub.example.com/abc")
	expect(t, res.Announce, ru)
	expect(t, res.HwidActive, true)
	expect(t, res.HwidLimit, true)
	expect(t, res.HwidMaxDevices, true)
	expect(t, res.HwidNotSupported, false)

	// Fetch is the same without extra headers.
	if _, err := Fetch(srv.URL+"/sub/token", "KlausVPN/1.2 (Android)", 5000, ""); err != nil {
		t.Fatal(err)
	}
	if got.Get("X-Hwid") != "" {
		t.Error("Fetch must not send device headers")
	}

	// Bad header JSON fails before anything is sent.
	if _, err := FetchWithHeaders(srv.URL, "", `{"bad name":"x"}`, 5000, ""); err == nil {
		t.Error("expected an error for a bad header name")
	}
}

func TestFetchErrorsAndLimit(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/bad/SECRET-TOKEN-123":
			// What Caddy answers when the subscription page drops the socket.
			w.WriteHeader(http.StatusBadGateway)
		case "/big":
			w.Write(make([]byte, maxFetchBytes+1))
		case "/max":
			w.Write(make([]byte, maxFetchBytes))
		}
	}))
	defer srv.Close()

	_, err := FetchWithHeaders(srv.URL+"/bad/SECRET-TOKEN-123", "", `{"X-Hwid":"SECRET-HWID-42"}`, 5000, "")
	if err == nil || err.Error() != "HTTP 502 Bad Gateway" {
		t.Fatalf("got %v, want HTTP 502 Bad Gateway", err)
	}
	if _, err := Fetch(srv.URL+"/big", "", 5000, ""); err == nil || !strings.Contains(err.Error(), "too large") {
		t.Errorf("a body over 8 MB must be refused, got %v", err)
	}
	if res, err := Fetch(srv.URL+"/max", "", 5000, ""); err != nil || len(res.Body) != maxFetchBytes {
		t.Errorf("a body of exactly 8 MB must pass: %v", err)
	}
	if maxFetchBytes != 8<<20 {
		t.Errorf("subscription limit is %d bytes, want 8 MB", maxFetchBytes)
	}
}
