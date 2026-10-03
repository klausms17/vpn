package service

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/account"
	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/client/store"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

type fakeTunnel struct {
	mu    sync.Mutex
	calls []string
	// running is the server the tunnel runs; "a" unless set.
	running string
}

func (f *fakeTunnel) record(c string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, c)
}

func (f *fakeTunnel) Connect()    { f.record("connect") }
func (f *fakeTunnel) Disconnect() { f.record("disconnect") }
func (f *fakeTunnel) Reconnect()  { f.record("reconnect") }
func (f *fakeTunnel) Status() ipc.Status {
	f.mu.Lock()
	defer f.mu.Unlock()
	return ipc.Status{State: ipc.Connected, ProfileID: cmp.Or(f.running, "a"), ProfileName: "A"}
}

func (f *fakeTunnel) took() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return strings.Join(f.calls, ",")
}

func key(link string) model.Key {
	return model.Key{Name: "Сервер", Protocol: "vless", Address: "h.example", Port: 443, Link: link, Outbounds: json.RawMessage(`[{"tag":"proxy"}]`)}
}

type testEnv struct {
	h      *handler
	tunnel *fakeTunnel
	mu     sync.Mutex
	events []ipc.Event
	logs   []string
	// importing, if set, tells when an import reads its keys and holds it
	// until the test sends.
	importing chan struct{}
	// measured answers the pinger's checks by server name; batches counts
	// the servers of each batch.
	measured map[string]int64
	batches  []int
	// panel answers subscription downloads; opened counts windows opened.
	panel  func(url string) (*libxray.FetchResult, error)
	opened int
}

func newEnv(t *testing.T) *testEnv {
	env := &testEnv{tunnel: &fakeTunnel{}}
	dir := t.TempDir()
	broadcast := func(ev ipc.Event) {
		env.mu.Lock()
		defer env.mu.Unlock()
		env.events = append(env.events, ev)
	}
	profiles := store.New(filepath.Join(dir, "profiles.json"), func() model.ProfilesState { return model.ProfilesState{} }, nil, func(string) {})
	env.h = &handler{
		tunnel:   env.tunnel,
		profiles: profiles,
		settings: store.New(filepath.Join(dir, "settings.json"), defaultSettings, nil, func(string) {}),
		pinger: newPinger(func(servers []model.StoredProfile) []int64 {
			env.mu.Lock()
			env.batches = append(env.batches, len(servers))
			env.mu.Unlock()
			out := make([]int64, len(servers))
			for i, s := range servers {
				var obs []map[string]any
				json.Unmarshal(s.Outbounds, &obs)
				name, _ := obs[0]["name"].(string)
				out[i] = -1
				if ms, ok := env.measured[name]; ok {
					out[i] = ms
				}
			}
			return out
		}, profiles.ReadStrict, func(p ipc.Pings) { broadcast(ipc.NewEvent(ipc.EventPings, p)) }),
		keys: func(ctx context.Context, text string) ([]model.Key, []string, error) {
			if env.importing != nil {
				env.importing <- struct{}{}
				<-env.importing
			}
			if text == "плохо" {
				return nil, nil, errors.New("Не найдено ни одного ключа")
			}
			var keys []model.Key
			for _, l := range strings.Fields(text) {
				keys = append(keys, key(l))
			}
			return keys, []string{"bogus"}[:strings.Count(text, "bogus")], nil
		},
		site: func(e string) string {
			if strings.Contains(e, " ") {
				return ""
			}
			return strings.ToLower(e)
		},
		logs: func() ipc.Logs {
			return ipc.Logs{Sections: []ipc.LogSection{{Title: "Kirov VPN", Text: "service started\n"}}}
		},
		broadcast: broadcast,
		log: func(m string) {
			env.mu.Lock()
			defer env.mu.Unlock()
			env.logs = append(env.logs, m)
		},
		now: func() time.Time { return time.UnixMilli(1234) },
	}
	env.h.opened = func() {
		env.mu.Lock()
		defer env.mu.Unlock()
		env.opened++
	}
	env.h.subs = newSubscriptions(env.h, func(_ context.Context, url string) (*libxray.FetchResult, error) {
		if env.panel == nil {
			return nil, errors.New("HTTP 404 Not Found")
		}
		return env.panel(url)
	}, func() int64 { return 1234 })
	env.h.accounts = newAccounts(env.h, nil, store.New(filepath.Join(dir, "account.json"),
		func() account.State { return account.State{} }, nil, func(string) {}), func() int64 { return 1234 })
	return env
}

// named returns the events of one name, in order.
func (env *testEnv) named(name string) []ipc.Event {
	env.mu.Lock()
	defer env.mu.Unlock()
	var out []ipc.Event
	for _, ev := range env.events {
		if ev.Event == name {
			out = append(out, ev)
		}
	}
	return out
}

func (env *testEnv) call(op string, args any) (any, error) {
	raw, _ := json.Marshal(args)
	return env.h.handle(context.Background(), op, raw)
}

func TestImportSavesNewKeysAndTellsTheWindows(t *testing.T) {
	env := newEnv(t)
	res, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: "  vless://one vless://two  "})
	if err != nil {
		t.Fatal(err)
	}
	if r := res.(ipc.ImportResult); r.Added != 2 || r.Message != "Добавлено серверов: 2" {
		t.Errorf("result %+v", r)
	}
	saved := env.h.profiles.Read()
	if len(saved.Profiles) != 2 || saved.SelectedID != saved.Profiles[0].ID || saved.Profiles[0].CreatedAt != 1234 {
		t.Errorf("saved %+v", saved)
	}
	if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(saved.Profiles[0].ID) {
		t.Errorf("id %q", saved.Profiles[0].ID)
	}
	if len(env.events) != 1 || env.events[0].Event != ipc.EventProfiles {
		t.Fatalf("events %v", env.events)
	}
	var p ipc.Profiles
	json.Unmarshal(env.events[0].Data, &p)
	if len(p.Profiles) != 2 || p.Profiles[0].Name != "Сервер" || p.SelectedID != saved.SelectedID {
		t.Errorf("profiles event %+v", p)
	}
	// The window never gets keys or links.
	if strings.Contains(string(env.events[0].Data), "vless://") || strings.Contains(string(env.events[0].Data), "proxy") {
		t.Errorf("keys in the event: %s", env.events[0].Data)
	}

	// The same keys again: nothing added, nothing broadcast.
	res, err = env.call(ipc.OpImport, ipc.ImportArgs{Text: "vless://two"})
	if err != nil || res.(ipc.ImportResult).Message != "Эти ключи уже добавлены" || len(env.events) != 1 {
		t.Errorf("again: %+v %v, %d events", res, err, len(env.events))
	}
	// Logged without the keys.
	for _, l := range env.logs {
		if strings.Contains(l, "vless") {
			t.Errorf("key in the log: %q", l)
		}
	}
}

func TestImportRefusals(t *testing.T) {
	env := newEnv(t)
	for _, c := range []struct{ text, want string }{
		{"   ", "Вставьте ключ сервера"},
		{strings.Repeat("a", ipc.MaxImport+1), "Слишком длинный текст: вставьте только ключи"},
		{"klausvpn://settings/x", "В ссылке нет ни ключа, ни подписки"},
		{"плохо", "Не найдено ни одного ключа"},
	} {
		if _, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: c.text}); err == nil || !strings.HasPrefix(err.Error(), c.want) {
			t.Errorf("%.20q: %v", c.text, err)
		}
	}
	if _, err := env.h.handle(context.Background(), ipc.OpImport, json.RawMessage(`{"text":1}`)); !errors.Is(err, errBadRequest) {
		t.Errorf("bad args: %v", err)
	}
	if len(env.h.profiles.Read().Profiles) != 0 {
		t.Error("saved something")
	}
}

func TestOneImportAtATime(t *testing.T) {
	env := newEnv(t)
	env.importing = make(chan struct{})
	first := make(chan error, 1)
	go func() {
		_, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: "vless://one"})
		first <- err
	}()
	<-env.importing // the first one runs
	if _, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: "vless://two"}); !errors.Is(err, errImporting) {
		t.Errorf("second import: %v", err)
	}
	env.importing <- struct{}{}
	if err := <-first; err != nil {
		t.Fatal(err)
	}
	env.importing = nil
	if _, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: "vless://two"}); err != nil {
		t.Errorf("after it: %v", err)
	}
}

func TestImportIntoAFullList(t *testing.T) {
	env := newEnv(t)
	links := make([]string, model.MaxProfiles+5)
	for i := range links {
		links[i] = fmt.Sprintf("vless://%d", i)
	}
	res, err := env.call(ipc.OpImport, ipc.ImportArgs{Text: strings.Join(links, "\n")})
	if err != nil {
		t.Fatal(err)
	}
	want := fmt.Sprintf("Добавлено серверов: %d. Больше %d серверов сохранить нельзя.", model.MaxProfiles, model.MaxProfiles)
	if r := res.(ipc.ImportResult); r.Added != model.MaxProfiles || r.Message != want {
		t.Errorf("result %+v", r)
	}
	if n := len(env.h.profiles.Read().Profiles); n != model.MaxProfiles {
		t.Errorf("%d saved", n)
	}
	// The list still fits in one message to the window.
	if line, err := json.Marshal(ipc.NewEvent(ipc.EventProfiles, env.h.profilesData())); err != nil || len(line) > ipc.MaxMessage {
		t.Errorf("profiles event of %d bytes, %v", len(line), err)
	}
}

func TestConnectDisconnectAndStatus(t *testing.T) {
	env := newEnv(t)
	env.call(ipc.OpConnect, nil)
	env.call(ipc.OpDisconnect, nil)
	if got := env.tunnel.took(); got != "connect,disconnect" {
		t.Errorf("calls %v", got)
	}
	st, err := env.call(ipc.OpStatus, nil)
	if err != nil || st.(ipc.Status).ProfileID != "a" {
		t.Errorf("%+v %v", st, err)
	}
	if _, err := env.call("rm -rf", nil); !errors.Is(err, ipc.ErrUnknownOp) {
		t.Errorf("unknown op: %v", err)
	}
}

func TestGreeting(t *testing.T) {
	env := newEnv(t)
	evs := env.h.greet()
	if len(evs) != 5 || evs[0].Event != ipc.EventStatus || evs[1].Event != ipc.EventProfiles || evs[2].Event != ipc.EventPings ||
		evs[3].Event != ipc.EventSettings || evs[4].Event != ipc.EventAccount {
		t.Fatalf("greeting %v", evs)
	}
	// A build without an accounts service says so: the window hides it.
	if string(evs[4].Data) != `{"available":false}` {
		t.Errorf("account %s", evs[4].Data)
	}
	// An empty list is [], never null: the window iterates it.
	if !strings.Contains(string(evs[1].Data), `"profiles":[]`) {
		t.Errorf("profiles %s", evs[1].Data)
	}
}

// servers saves servers named after names and returns their ids.
func (env *testEnv) servers(t *testing.T, names ...string) []string {
	t.Helper()
	var keys []model.Key
	for _, n := range names {
		k := key("vless://" + n)
		k.Name = n
		k.Outbounds = json.RawMessage(fmt.Sprintf(`[{"tag":"proxy","name":%q}]`, n))
		keys = append(keys, k)
	}
	saved, err := env.h.profiles.Update(func(s model.ProfilesState) model.ProfilesState {
		next, _, _ := s.WithNewKeys(keys, newID, 1)
		return next
	})
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, p := range saved.Profiles {
		out = append(out, p.ID)
	}
	return out
}

func TestSelectingAnotherServerMovesTheTunnel(t *testing.T) {
	env := newEnv(t)
	ids := env.servers(t, "de", "nl")
	if _, err := env.call(ipc.OpSelect, ipc.IDArgs{ID: ids[1]}); err != nil {
		t.Fatal(err)
	}
	if got := env.h.profiles.Read().SelectedID; got != ids[1] {
		t.Errorf("selected %q", got)
	}
	if env.tunnel.took() != "reconnect" || len(env.named(ipc.EventProfiles)) != 1 {
		t.Errorf("tunnel %q, events %v", env.tunnel.took(), env.events)
	}
	// The same server again changes nothing.
	if _, err := env.call(ipc.OpSelect, ipc.IDArgs{ID: ids[1]}); err != nil || env.tunnel.took() != "reconnect" {
		t.Errorf("again: %v, tunnel %q", err, env.tunnel.took())
	}
	if _, err := env.call(ipc.OpSelect, ipc.IDArgs{ID: "gone"}); !errors.Is(err, errNoServer) {
		t.Errorf("a server deleted meanwhile: %v", err)
	}
}

func TestRename(t *testing.T) {
	env := newEnv(t)
	ids := env.servers(t, "de")
	if _, err := env.call(ipc.OpRename, ipc.RenameArgs{ID: ids[0], Name: "  Дом  "}); err != nil {
		t.Fatal(err)
	}
	if got := env.h.profiles.Read().Profiles[0].Name; got != "Дом" {
		t.Errorf("name %q", got)
	}
	if _, err := env.call(ipc.OpRename, ipc.RenameArgs{ID: ids[0], Name: "  "}); err == nil {
		t.Error("an empty name was taken")
	}
	if _, err := env.call(ipc.OpRename, ipc.RenameArgs{ID: "gone", Name: "x"}); !errors.Is(err, errNoServer) {
		t.Errorf("unknown server: %v", err)
	}
	if env.tunnel.took() != "" {
		t.Errorf("tunnel %q", env.tunnel.took())
	}
}

func TestDeletingTheSelectedServerMovesOrStopsTheTunnel(t *testing.T) {
	env := newEnv(t)
	ids := env.servers(t, "de", "nl", "fi")
	// Not the selected one: the tunnel stays as it is.
	env.call(ipc.OpDelete, ipc.IDArgs{ID: ids[2]})
	if env.tunnel.took() != "" {
		t.Errorf("tunnel %q", env.tunnel.took())
	}
	// The selected one: on to the next server.
	env.call(ipc.OpDelete, ipc.IDArgs{ID: ids[0]})
	if got := env.h.profiles.Read(); len(got.Profiles) != 1 || got.SelectedID != ids[1] || env.tunnel.took() != "reconnect" {
		t.Errorf("saved %+v, tunnel %q", got, env.tunnel.took())
	}
	// The last one: the tunnel stops.
	env.call(ipc.OpDelete, ipc.IDArgs{ID: ids[1]})
	if env.tunnel.took() != "reconnect,disconnect" {
		t.Errorf("tunnel %q", env.tunnel.took())
	}
	if n := len(env.named(ipc.EventProfiles)); n != 3 {
		t.Errorf("%d profiles events", n)
	}
}

// pingsWhenDone waits until no check runs and their results went out,
// in an event after the first seen ones.
func (env *testEnv) pingsWhenDone(t *testing.T, seen int) ipc.Pings {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		evs := env.named(ipc.EventPings)
		if len(evs) > seen {
			var p ipc.Pings
			json.Unmarshal(evs[len(evs)-1].Data, &p)
			done := true
			for _, x := range p {
				done = done && x.State != ipc.PingTesting
			}
			if done && len(p) > 0 {
				return p
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("the checks did not finish")
	return nil
}

func TestPingChecksServersAndTellsTheWindows(t *testing.T) {
	env := newEnv(t)
	env.measured = map[string]int64{"de": 120}
	ids := env.servers(t, "de", "nl", "fi")
	if _, err := env.call(ipc.OpPing, ipc.PingArgs{IDs: ids[:2]}); err != nil {
		t.Fatal(err)
	}
	got := env.pingsWhenDone(t, 0)
	want := ipc.Pings{ids[0]: {State: ipc.PingOK, Ms: 120}, ids[1]: {State: ipc.PingFailed}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("pings %+v", got)
	}
	// All of them; a deleted server's result goes.
	seen := len(env.named(ipc.EventPings))
	env.call(ipc.OpPing, ipc.PingArgs{})
	if got := env.pingsWhenDone(t, seen); len(got) != 3 {
		t.Errorf("pings %+v", got)
	}
	env.call(ipc.OpDelete, ipc.IDArgs{ID: ids[2]})
	if _, ok := env.h.pinger.current()[ids[2]]; ok {
		t.Error("the deleted server's check stayed")
	}
	// A new window gets them.
	var greeted ipc.Pings
	json.Unmarshal(env.h.greet()[2].Data, &greeted)
	if len(greeted) != 2 {
		t.Errorf("greeting %+v", greeted)
	}
}

func TestSettingsAreCheckedSavedAndApplied(t *testing.T) {
	env := newEnv(t)
	s := defaultSettings()
	s.Mode = ipc.ModeGlobal
	s.DirectSites = []string{"Bank.example", "bank.example"}
	// Names stay as Windows spells them: Xray matches them exactly.
	s.DirectPrograms = []string{"Telegram.exe", " Telegram.exe", "GAME.EXE"}
	if _, err := env.call(ipc.OpSetSettings, s); err != nil {
		t.Fatal(err)
	}
	saved := env.h.settings.Read()
	if saved.Mode != ipc.ModeGlobal || !reflect.DeepEqual(saved.DirectSites, []string{"bank.example"}) || !reflect.DeepEqual(saved.DirectPrograms, []string{"Telegram.exe", "GAME.EXE"}) || !saved.TorrentsDirect || !saved.AutoConnect {
		t.Errorf("saved %+v", saved)
	}
	if env.tunnel.took() != "reconnect" || len(env.named(ipc.EventSettings)) != 1 {
		t.Errorf("tunnel %q, events %v", env.tunnel.took(), env.events)
	}
	// The same settings again change nothing.
	env.call(ipc.OpSetSettings, s)
	if env.tunnel.took() != "reconnect" {
		t.Errorf("tunnel %q", env.tunnel.took())
	}
	for _, c := range []struct {
		change func(*ipc.Settings)
		want   string
	}{
		{func(s *ipc.Settings) { s.Mode = "all" }, "неверный запрос"},
		{func(s *ipc.Settings) { s.ProxySites = []string{"not a site"} }, "«not a site» не похоже на сайт или адрес"},
		{func(s *ipc.Settings) { s.ProxyPrograms = []string{`C:\Tools\x.exe`} }, "не похоже на программу"},
		{func(s *ipc.Settings) { s.ProxyPrograms = []string{"notes.txt"} }, "не похоже на программу"},
		{func(s *ipc.Settings) { s.ProxyPrograms = []string{"..exe"} }, "не похоже на программу"},
		{func(s *ipc.Settings) { s.ProxyPrograms = []string{"a\x01.exe"} }, "не похоже на программу"},
		{func(s *ipc.Settings) { s.BlockSites = make([]string, maxSites+1) }, "Слишком много сайтов"},
		{func(s *ipc.Settings) {
			for i := range maxRegexps + 1 {
				s.BlockSites = append(s.BlockSites, fmt.Sprintf("regexp:^ad%d", i))
			}
		}, "Слишком много правил regexp"},
		{func(s *ipc.Settings) {
			for i := range maxSites {
				s.ProxySites = append(s.ProxySites, fmt.Sprintf("keyword:%0250d", i))
				s.DirectSites = append(s.DirectSites, fmt.Sprintf("keyword:%0250d", i))
			}
		}, "Слишком много правил"},
	} {
		bad := defaultSettings()
		c.change(&bad)
		if _, err := env.call(ipc.OpSetSettings, bad); err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v", clip(fmt.Sprintf("%+v", bad)), err)
		}
	}
	if got := env.h.settings.Read(); got.Mode != ipc.ModeGlobal {
		t.Errorf("a refused change was saved: %+v", got)
	}
}

func TestLogsGoToTheWindow(t *testing.T) {
	env := newEnv(t)
	res, err := env.call(ipc.OpLogs, nil)
	if err != nil || len(res.(ipc.Logs).Sections) != 1 {
		t.Errorf("%+v %v", res, err)
	}
}

func TestConnectingAtBootIsNoReasonToRestart(t *testing.T) {
	env := newEnv(t)
	s := defaultSettings()
	s.AutoConnect = false
	if _, err := env.call(ipc.OpSetSettings, s); err != nil {
		t.Fatal(err)
	}
	if env.tunnel.took() != "" || len(env.named(ipc.EventSettings)) != 1 {
		t.Errorf("tunnel %q, events %v", env.tunnel.took(), env.events)
	}
}

func TestTheJournalIsReadOncePerSecond(t *testing.T) {
	env := newEnv(t)
	reads := 0
	logs := env.h.logs
	env.h.logs = func() ipc.Logs {
		reads++
		return logs()
	}
	now := time.UnixMilli(5000)
	env.h.now = func() time.Time { return now }
	for range 3 {
		env.call(ipc.OpLogs, nil)
	}
	now = now.Add(logsFresh)
	env.call(ipc.OpLogs, nil)
	if reads != 2 {
		t.Errorf("%d reads", reads)
	}
}

func TestChecksRunInBatchesOneAtATime(t *testing.T) {
	env := newEnv(t)
	ids := env.servers(t, "de", "nl", "fi")
	started := make(chan int, 4)
	release := make(chan struct{})
	env.h.pinger.probe = func(servers []model.StoredProfile) []int64 {
		started <- len(servers)
		<-release
		return make([]int64, len(servers))
	}
	env.call(ipc.OpPing, ipc.PingArgs{IDs: ids[:1]})
	if n := <-started; n != 1 {
		t.Fatalf("the first batch checked %d servers", n)
	}
	// Asked for while it runs, the others wait for one batch together, and
	// one deleted before it starts is not checked.
	env.call(ipc.OpPing, ipc.PingArgs{IDs: ids[1:]})
	env.call(ipc.OpDelete, ipc.IDArgs{ID: ids[2]})
	select {
	case n := <-started:
		t.Fatalf("a second batch of %d ran alongside", n)
	case <-time.After(50 * time.Millisecond):
	}
	release <- struct{}{}
	if n := <-started; n != 1 {
		t.Fatalf("the next batch checked %d servers, want the one left", n)
	}
	release <- struct{}{}
	got := env.pingsWhenDone(t, 0)
	want := ipc.Pings{ids[0]: {State: ipc.PingOK}, ids[1]: {State: ipc.PingOK}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("pings %+v", got)
	}
}

// TestEverySettingCounts: a setting added to ipc.Settings must be compared
// too, or changing it would not reach the tunnel or the windows.
func TestEverySettingCounts(t *testing.T) {
	base := defaultSettings()
	v := reflect.ValueOf(&base).Elem()
	for i := range v.NumField() {
		changed := base
		f := reflect.ValueOf(&changed).Elem().Field(i)
		switch f.Kind() {
		case reflect.String:
			f.SetString("other")
		case reflect.Bool:
			f.SetBool(!f.Bool())
		case reflect.Slice:
			f.Set(reflect.ValueOf([]string{"x"}))
		default:
			t.Fatalf("%s: a kind of setting the test does not know", v.Type().Field(i).Name)
		}
		if equalSettings(base, changed) {
			t.Errorf("%s is not compared", v.Type().Field(i).Name)
		}
		if routing := v.Type().Field(i).Name != "AutoConnect"; sameRouting(base, changed) == routing {
			t.Errorf("%s: routing %v", v.Type().Field(i).Name, routing)
		}
	}
	empty := base
	empty.DirectSites = []string{}
	if !equalSettings(base, empty) {
		t.Error("an empty list differs from none")
	}
}
