package service

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

const (
	subLink = "https://sub.example.com/abc"
	keyDE   = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome&flow=xtls-rprx-vision#Германия"
	keyNL   = "vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome&flow=xtls-rprx-vision#Нидерланды"
)

// serves makes the panel answer with keys under a title.
func (env *testEnv) serves(title string, keys ...string) {
	b := []byte(base64.StdEncoding.EncodeToString([]byte(strings.Join(keys, "\n"))))
	env.mu.Lock()
	defer env.mu.Unlock()
	env.panel = func(string) (*libxray.FetchResult, error) {
		return &libxray.FetchResult{Body: b, ProfileTitle: title, UserInfo: "upload=1; download=2; total=1000; expire=1900000000", Announce: "Привет"}, nil
	}
}

// last is the newest servers event.
func (env *testEnv) last(t *testing.T) ipc.Profiles {
	t.Helper()
	evs := env.named(ipc.EventProfiles)
	if len(evs) == 0 {
		t.Fatal("no servers event")
	}
	var p ipc.Profiles
	if err := json.Unmarshal(evs[len(evs)-1].Data, &p); err != nil {
		t.Fatal(err)
	}
	return p
}

func (env *testEnv) importText(t *testing.T, text string) ipc.ImportResult {
	t.Helper()
	res, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: text})
	if err != nil {
		t.Fatal(err)
	}
	return res.(ipc.ImportResult)
}

func TestASubscriptionIsAddedFromItsLink(t *testing.T) {
	env := newEnv(t)
	env.serves("Kirov VPN", keyDE, keyNL)
	if r := env.importText(t, "Моя подписка: "+subLink); r.Added != 2 || r.Message != "Подписка «Kirov VPN»: серверов 2" {
		t.Errorf("result %+v", r)
	}
	p := env.last(t)
	if len(p.Subscriptions) != 1 || len(p.Profiles) != 2 {
		t.Fatalf("event %+v", p)
	}
	s := p.Subscriptions[0]
	if s.Name != "Kirov VPN" || s.Used != 3 || s.Total != 1000 || s.Expire != 1900000000 || s.Announce != "Привет" || s.UpdatedAt != 1234 {
		t.Errorf("subscription %+v", s)
	}
	if p.Profiles[0].SubscriptionID != s.ID || p.Profiles[0].Network != "raw" || p.Profiles[0].Security != "reality" || p.SelectedID != p.Profiles[0].ID {
		t.Errorf("server %+v", p.Profiles[0])
	}
	// The windows never get the link or keys.
	for _, ev := range env.named(ipc.EventProfiles) {
		if d := string(ev.Data); strings.Contains(d, "sub.example.com") || strings.Contains(d, "://") || strings.Contains(d, "pbk") {
			t.Errorf("link in an event: %s", d)
		}
	}
	if _, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: subLink}); err == nil || err.Error() != "Эта подписка уже добавлена" {
		t.Errorf("again: %v", err)
	}
}

func TestAnAddToKirovVPNLinkIsReadFirst(t *testing.T) {
	env := newEnv(t)
	env.serves("", keyDE)
	var asked string
	panel := env.panel
	env.panel = func(url string) (*libxray.FetchResult, error) {
		asked = url
		return panel(url)
	}
	r := env.importText(t, "klausvpn://add/"+subLink+"#Ivan")
	if r.Message != "Подписка «sub.example.com»: серверов 1" || asked != subLink {
		t.Errorf("result %+v, asked %q", r, asked)
	}
	// A key in such a link is a key.
	if r := env.importText(t, "klausvpn://import/vless://one"); r.Added != 1 {
		t.Errorf("key: %+v", r)
	}
}

func TestAFailedAddSaysWhyAndSavesNothing(t *testing.T) {
	env := newEnv(t)
	if _, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: subLink}); err == nil || err.Error() != "HTTP 404 Not Found" {
		t.Errorf("err %v", err)
	}
	if s := env.h.profiles.Read(); len(s.Subscriptions) != 0 {
		t.Errorf("saved %+v", s)
	}
}

func TestRefreshReplacesTheServersAndMovesTheTunnel(t *testing.T) {
	env := newEnv(t)
	env.serves("Kirov VPN", keyDE, keyNL)
	env.importText(t, subLink)
	saved := env.h.profiles.Read()
	id, de := saved.Subscriptions[0].ID, saved.Profiles[0].ID
	env.tunnel.running = de

	env.serves("Kirov VPN", keyNL)
	res, err := env.call(ipc.OpRefresh, ipc.IDArgs{ID: id})
	if err != nil || res.(ipc.ImportResult).Message != "Подписка обновлена: серверов 1" {
		t.Fatalf("%+v, %v", res, err)
	}
	// The running server is gone: the tunnel moves to the next one.
	if got := env.tunnel.took(); got != "reconnect" {
		t.Errorf("tunnel %q", got)
	}
	if p := env.last(t); len(p.Profiles) != 1 || p.SelectedID == de || p.Subscriptions[0].Updating {
		t.Errorf("event %+v", p)
	}
	// Refreshing all with one subscription is refreshing it.
	if res, err := env.call(ipc.OpRefresh, ipc.IDArgs{}); err != nil || res.(ipc.ImportResult).Message != "Подписка обновлена: серверов 1" {
		t.Errorf("all: %+v, %v", res, err)
	}
}

func TestAFailedRefreshKeepsTheServersAndSaysWhy(t *testing.T) {
	env := newEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	id := env.h.profiles.Read().Subscriptions[0].ID
	env.panel = func(string) (*libxray.FetchResult, error) { return nil, errors.New("HTTP 502 Bad Gateway") }
	if _, err := env.call(ipc.OpRefresh, ipc.IDArgs{ID: id}); err == nil || err.Error() != "Не удалось обновить подписку: HTTP 502 Bad Gateway" {
		t.Errorf("err %v", err)
	}
	if p := env.last(t); len(p.Profiles) != 1 || p.Subscriptions[0].LastError != "HTTP 502 Bad Gateway" {
		t.Errorf("event %+v", p)
	}
	if _, err := env.call(ipc.OpRefresh, ipc.IDArgs{ID: "nope"}); !errors.Is(err, errNoSubscription) {
		t.Errorf("unknown: %v", err)
	}
}

func TestRefreshingAllSaysWhichFailed(t *testing.T) {
	env := newEnv(t)
	env.serves("Первая", keyDE)
	env.importText(t, subLink)
	env.serves("Вторая", keyNL)
	env.importText(t, "https://other.example.com/x")
	env.panel = func(url string) (*libxray.FetchResult, error) {
		if url == subLink {
			return nil, errors.New("HTTP 502 Bad Gateway")
		}
		return &libxray.FetchResult{Body: []byte(base64.StdEncoding.EncodeToString([]byte(keyNL)))}, nil
	}
	res, err := env.call(ipc.OpRefresh, ipc.IDArgs{})
	if want := "Обновлено подписок: 1 из 2. Не удалось обновить «Первая»: HTTP 502 Bad Gateway"; err != nil || res.(ipc.ImportResult).Message != want {
		t.Errorf("%+v, %v", res, err)
	}
}

func TestASubscriptionShowsAsUpdatingWhileItDownloads(t *testing.T) {
	env := newEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	id := env.h.profiles.Read().Subscriptions[0].ID
	answered := env.panel
	reached, release := make(chan struct{}), make(chan struct{})
	env.panel = func(url string) (*libxray.FetchResult, error) {
		close(reached)
		<-release
		return answered(url)
	}
	done := make(chan error)
	go func() {
		_, err := env.call(ipc.OpRefresh, ipc.IDArgs{ID: id})
		done <- err
	}()
	<-reached
	if p := env.last(t); !p.Subscriptions[0].Updating {
		t.Error("not shown as updating")
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if p := env.last(t); p.Subscriptions[0].Updating {
		t.Error("still shown as updating")
	}
}

func TestDeletingASubscriptionTakesItsServers(t *testing.T) {
	env := newEnv(t)
	env.importText(t, "vless://own")
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	s := env.h.profiles.Read()
	// The selection stays on the own key.
	if _, err := env.call(ipc.OpDeleteSubscription, ipc.IDArgs{ID: s.Subscriptions[0].ID}); err != nil {
		t.Fatal(err)
	}
	if p := env.last(t); len(p.Subscriptions) != 0 || len(p.Profiles) != 1 || p.SelectedID != s.SelectedID || env.tunnel.took() != "" {
		t.Errorf("event %+v, tunnel %q", p, env.tunnel.took())
	}
	if _, err := env.call(ipc.OpDeleteSubscription, ipc.IDArgs{ID: s.Subscriptions[0].ID}); !errors.Is(err, errNoSubscription) {
		t.Errorf("again: %v", err)
	}

	// The selected server was in it, and nothing is left: the tunnel stops.
	env = newEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	if _, err := env.call(ipc.OpDeleteSubscription, ipc.IDArgs{ID: env.h.profiles.Read().Subscriptions[0].ID}); err != nil || env.tunnel.took() != "disconnect" {
		t.Errorf("err %v, tunnel %q", err, env.tunnel.took())
	}
}

func TestDueSubscriptionsAreRefreshedQuietly(t *testing.T) {
	env := newEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	// A window opening asks for it.
	env.h.greet()
	if env.opened != 1 {
		t.Errorf("opened %d", env.opened)
	}
	var asked int
	env.panel = func(string) (*libxray.FetchResult, error) {
		asked++
		return nil, errors.New("HTTP 502 Bad Gateway")
	}
	// Fresh: nothing to do.
	env.h.subs.refreshStale(context.Background())
	if asked != 0 {
		t.Fatalf("a fresh subscription was downloaded %d times", asked)
	}
	// An hour on: downloaded; the failure is saved and only logged.
	env.h.subs.now = func() int64 { return 1234 + 3_600_000 }
	env.h.subs.refreshStale(context.Background())
	if asked != 1 || env.h.profiles.Read().Subscriptions[0].LastError != "HTTP 502 Bad Gateway" {
		t.Errorf("asked %d, %+v", asked, env.h.profiles.Read().Subscriptions[0])
	}
}

func TestARefreshOfNoSubscriptionChangesNothing(t *testing.T) {
	env := newEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	events := len(env.named(ipc.EventProfiles))
	if _, err := env.call(ipc.OpRefresh, ipc.IDArgs{ID: "no-such-id"}); !errors.Is(err, errNoSubscription) {
		t.Errorf("err %v", err)
	}
	if n := len(env.named(ipc.EventProfiles)); n != events {
		t.Errorf("%d servers events for nothing", n-events)
	}
}

// Refreshes wait for the download that runs; one whose window went away
// gives up without downloading.
func TestAWaitingRefreshGivesUpWithItsWindow(t *testing.T) {
	env := newEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	id := env.h.profiles.Read().Subscriptions[0].ID
	answered := env.panel
	reached, release := make(chan struct{}), make(chan struct{})
	var downloads int
	env.panel = func(url string) (*libxray.FetchResult, error) {
		downloads++
		if downloads == 1 {
			close(reached)
			<-release
		}
		return answered(url)
	}
	done := make(chan error)
	go func() {
		_, err := env.call(ipc.OpRefresh, ipc.IDArgs{ID: id})
		done <- err
	}()
	<-reached
	ctx, cancel := context.WithCancel(context.Background())
	waiting := make(chan error)
	go func() {
		_, err := env.h.subs.refresh(ctx, id)
		waiting <- err
	}()
	cancel()
	if err := <-waiting; !errors.Is(err, context.Canceled) {
		t.Errorf("the waiting refresh: %v", err)
	}
	// The first one still shows as updating, and finishes.
	if p := env.last(t); !p.Subscriptions[0].Updating {
		t.Error("the running refresh no longer shows")
	}
	close(release)
	if err := <-done; err != nil || downloads != 1 {
		t.Errorf("err %v, %d downloads", err, downloads)
	}
	if p := env.last(t); p.Subscriptions[0].Updating {
		t.Error("still shown as updating")
	}
}

func TestServersThatCannotBeUsedAreCounted(t *testing.T) {
	env := newEnv(t)
	// Its certificate cannot be fetched: the server is left out.
	insecure := "vless://11111111-2222-3333-4444-555555555555@127.0.0.1:1?security=tls&allowInsecure=1&type=tcp#self"
	env.serves("Kirov VPN", keyDE, insecure)
	r := env.importText(t, subLink)
	if r.Added != 1 || !strings.HasPrefix(r.Message, "Подписка «Kirov VPN»: серверов 1. Пропущено: 1 (self: не удалось получить сертификат сервера") {
		t.Errorf("result %+v", r)
	}
}
