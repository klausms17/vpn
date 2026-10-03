package subscription

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/importer"
	"github.com/klausms17/vpn/libxray/client/model"
)

const (
	keyDE = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome&flow=xtls-rprx-vision#Германия"
	keyNL = "vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome&flow=xtls-rprx-vision#Нидерланды"
	// insecureKey asks to skip certificate checks; its server (port 1 on
	// this machine) never answers.
	insecureKey = "vless://11111111-2222-3333-4444-555555555555@127.0.0.1:1?security=tls&allowInsecure=1&type=tcp#self"
)

func body(keys ...string) []byte {
	return []byte(base64.StdEncoding.EncodeToString([]byte(strings.Join(keys, "\n"))))
}

func answer(r libxray.FetchResult) Fetch {
	return func(context.Context, string) (*libxray.FetchResult, error) { return &r, nil }
}

func TestDownloadReadsTheListAndThePanelsHeaders(t *testing.T) {
	f, err := Download(context.Background(), "https://sub.example.com/x", answer(libxray.FetchResult{
		Body:         body(keyDE, keyNL),
		ProfileTitle: "Kirov VPN",
		UserInfo:     " upload=1; download=2; total=3; expire=4 ",
		SupportUrl:   "https://t.me/owner",
		Announce:     strings.Repeat("я", 600),
		ReportUrl:    "https://sub.example.com/klaus/report",
		AppUrl:       "http://sub.example.com/app/version.json",
	}), importer.ForService, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(f.Keys) != 2 || f.Keys[0].Name != "Германия" || f.Keys[1].Address != "nl.example.com" {
		t.Fatalf("keys %+v", f.Keys)
	}
	if f.Title != "Kirov VPN" || f.UserInfo != "upload=1; download=2; total=3; expire=4" || f.SupportURL != "https://t.me/owner" {
		t.Errorf("headers %+v", f)
	}
	if len([]rune(f.Announce)) != 500 || f.ReportURL != "https://sub.example.com/klaus/report" || f.AppURL != "" {
		t.Errorf("announce %d characters, report %q, app %q (http is not taken)", len([]rune(f.Announce)), f.ReportURL, f.AppURL)
	}
}

func TestTheDeviceLimitKeepsTheServers(t *testing.T) {
	for _, c := range []struct {
		r    libxray.FetchResult
		want string
	}{
		{libxray.FetchResult{HwidMaxDevices: true, Body: body(keyDE)}, HWIDLimit},
		{libxray.FetchResult{HwidLimit: true}, HWIDLimit},
		{libxray.FetchResult{HwidNotSupported: true, HwidLimit: true}, HWIDNotSupported},
	} {
		f, err := Download(context.Background(), "https://sub.example.com/x", answer(c.r), nil, nil, nil)
		if err != nil || len(f.Keys) != 0 || f.Notice != c.want {
			t.Errorf("%+v: keys %d, notice %q, err %v", c.r, len(f.Keys), f.Notice, err)
		}
	}
}

func TestPlaceholdersBecomeTheNotice(t *testing.T) {
	placeholder := "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?type=tcp&security=none#Подписка закончилась"
	f, err := Download(context.Background(), "https://sub.example.com/x", answer(libxray.FetchResult{Body: body(placeholder)}), nil, nil, nil)
	if err != nil || len(f.Keys) != 0 || f.Notice != "Подписка закончилась" {
		t.Errorf("keys %d, notice %q, err %v", len(f.Keys), f.Notice, err)
	}
}

func TestThePanelsTextsAreBounded(t *testing.T) {
	var placeholders []string
	for i := range 3 {
		placeholders = append(placeholders, fmt.Sprintf("vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?type=tcp&security=none#%d%s", i, strings.Repeat("я", 300)))
	}
	f, err := Download(context.Background(), "https://sub.example.com/x", answer(libxray.FetchResult{
		Body: body(placeholders...), UserInfo: strings.Repeat("total=1;", 100),
	}), nil, nil, nil)
	if err != nil || len([]rune(f.Notice)) != maxPanelText || len([]rune(f.UserInfo)) != maxPanelText {
		t.Errorf("notice of %d characters, user info of %d, err %v", len([]rune(f.Notice)), len([]rune(f.UserInfo)), err)
	}
	s := MarkFailed(model.ProfilesState{Subscriptions: []model.Subscription{{ID: "s"}}}, "s", strings.Repeat("e", 2000), 1)
	if n := len([]rune(s.Subscriptions[0].LastError)); n != maxPanelText {
		t.Errorf("error of %d characters", n)
	}
}

func TestServersThatCannotBeUsedAreAnError(t *testing.T) {
	xdrive := "vless://11111111-2222-3333-4444-555555555555@h.example.com:443?type=xhttp&security=tls&extra=" +
		"%7B%22downloadSettings%22%3A%7B%22network%22%3A%22xdrive%22%7D%7D#bad"
	_, err := Download(context.Background(), "https://sub.example.com/x", answer(libxray.FetchResult{Body: body(xdrive)}), importer.ForService, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "bad") {
		t.Errorf("err %v", err)
	}
	// One that can be used is enough; the other is reported.
	f, err := Download(context.Background(), "https://sub.example.com/x", answer(libxray.FetchResult{Body: body(xdrive, keyDE)}), importer.ForService, nil, nil)
	if err != nil || len(f.Keys) != 1 || len(f.Errors) != 1 {
		t.Errorf("keys %d, errors %q, err %v", len(f.Keys), f.Errors, err)
	}
}

func TestADownloadErrorIsTheError(t *testing.T) {
	failing := func(context.Context, string) (*libxray.FetchResult, error) {
		return nil, errors.New("HTTP 502 Bad Gateway")
	}
	if _, err := Download(context.Background(), "https://sub.example.com/x", failing, nil, nil, nil); err == nil || err.Error() != "HTTP 502 Bad Gateway" {
		t.Errorf("err %v", err)
	}
	if _, err := Download(context.Background(), "https://sub.example.com/x", answer(libxray.FetchResult{Body: []byte("<html>")}), nil, nil, nil); err == nil {
		t.Error("a web page was taken for a subscription")
	}
}

// A refresh does not contact a server whose pinned certificate it already
// has, and keeps one it cannot reach.
func TestPinsOfSavedServers(t *testing.T) {
	saved := json.RawMessage(`[{"protocol":"vless","tag":"proxy","pinned":true}]`)
	other := strings.Replace(insecureKey, "type=tcp", "type=tcp&fp=chrome", 1)
	pinned := PinnedOf([]model.StoredProfile{{ID: "a", Protocol: "vless", Address: "127.0.0.1", Port: 1, Link: other, Outbounds: saved}})
	fetch := answer(libxray.FetchResult{Body: body(insecureKey)})

	// The very same link (names aside) reuses its pin.
	samePin := PinnedOf([]model.StoredProfile{{ID: "a", Protocol: "vless", Address: "127.0.0.1", Port: 1, Link: strings.TrimSuffix(insecureKey, "#self") + "#old name", Outbounds: saved}})
	f, err := Download(context.Background(), "https://sub.example.com/x", fetch, nil, samePin.SameLink, samePin.SameServer)
	if err != nil || len(f.Keys) != 1 || string(f.Keys[0].Outbounds) != string(saved) {
		t.Fatalf("reused: %+v, %v", f.Keys, err)
	}
	// Other settings: pinned again; the server does not answer, so it stays
	// as it was saved.
	f, err = Download(context.Background(), "https://sub.example.com/x", fetch, nil, pinned.SameLink, pinned.SameServer)
	if err != nil || len(f.Keys) != 1 || string(f.Keys[0].Outbounds) != string(saved) {
		t.Fatalf("kept: %+v, %v", f.Keys, err)
	}
	// A new server that cannot be pinned is left out.
	if _, err := Download(context.Background(), "https://sub.example.com/x", fetch, nil, nil, nil); err == nil || !strings.Contains(err.Error(), "сертификат") {
		t.Errorf("err %v", err)
	}
}

func TestHTTPSURL(t *testing.T) {
	for in, want := range map[string]string{
		" https://sub.example.com/klaus/report ": "https://sub.example.com/klaus/report",
		"https://[2001:db8::1]:8443/r?x=1":       "https://[2001:db8::1]:8443/r?x=1",
		"https://впн.example/app/version.json":   "https://впн.example/app/version.json",
		"HTTPS://Sub.Example.com/r":              "HTTPS://Sub.Example.com/r",
	} {
		if got := HTTPSURL(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
	for _, bad := range []string{
		"", "http://sub.example.com/r", "sub.example.com/r", "https://", "https:///r", "https://:443/r",
		"https://user:pw@sub.example.com/r", "https://sub.example.com/a b", "https://sub.example.com/\x00",
		"javascript:alert(1)", "https://sub.example.com/" + strings.Repeat("a", 1000), "https://[/r",
	} {
		if got := HTTPSURL(bad); got != "" {
			t.Errorf("%.40q taken as %q", bad, got)
		}
	}
}

func TestDecodeTitle(t *testing.T) {
	for in, want := range map[string]string{
		"  Kirov VPN ": "Kirov VPN",
		"base64:" + base64.StdEncoding.EncodeToString([]byte(" Друзья ")): "Друзья",
		"base64:0JTRgNGD0LfRjNGP": "Друзья",
		"base64:!!!":              "",
		"":                        "",
	} {
		if got := decodeTitle(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestParseUsage(t *testing.T) {
	for in, want := range map[string]Usage{
		"upload=1; download=2; total=3; expire=4": {Used: 3, Total: 3, Expire: 4},
		"UPLOAD = 10;download=5;total=x;expire=":  {Used: 15},
		"total=1073741824":                        {Total: 1 << 30},
		"":                                        {},
		"garbage; upload; =5; download=-1; expire=1e9 ": {Used: -1},
	} {
		if got := ParseUsage(in); got != want {
			t.Errorf("%q: %+v, want %+v", in, got, want)
		}
	}
}
