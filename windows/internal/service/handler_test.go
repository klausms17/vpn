package service

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/client/store"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

type fakeTunnel struct {
	mu    sync.Mutex
	calls []string
}

func (f *fakeTunnel) Connect() { f.mu.Lock(); f.calls = append(f.calls, "connect"); f.mu.Unlock() }
func (f *fakeTunnel) Disconnect() {
	f.mu.Lock()
	f.calls = append(f.calls, "disconnect")
	f.mu.Unlock()
}
func (f *fakeTunnel) Status() ipc.Status {
	return ipc.Status{State: ipc.Connected, ProfileID: "a", ProfileName: "A"}
}

func key(link string) model.Key {
	return model.Key{Name: "Сервер", Protocol: "vless", Address: "h.example", Port: 443, Link: link, Outbounds: json.RawMessage(`[{"tag":"proxy"}]`)}
}

type testEnv struct {
	h      *handler
	tunnel *fakeTunnel
	events []ipc.Event
	logs   []string
}

func newEnv(t *testing.T) *testEnv {
	env := &testEnv{tunnel: &fakeTunnel{}}
	env.h = &handler{
		tunnel:   env.tunnel,
		profiles: store.New(filepath.Join(t.TempDir(), "profiles.json"), func() model.ProfilesState { return model.ProfilesState{} }, nil, func(string) {}),
		keys: func(text string) ([]model.Key, []string, error) {
			if text == "плохо" {
				return nil, nil, errors.New("Не найдено ни одного ключа")
			}
			var keys []model.Key
			for _, l := range strings.Fields(text) {
				keys = append(keys, key(l))
			}
			return keys, []string{"bogus"}[:strings.Count(text, "bogus")], nil
		},
		broadcast: func(ev ipc.Event) { env.events = append(env.events, ev) },
		log:       func(m string) { env.logs = append(env.logs, m) },
		now:       func() time.Time { return time.UnixMilli(1234) },
	}
	return env
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
		{"https://panel.example/sub/abc", "Подписки появятся"},
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

func TestConnectDisconnectAndStatus(t *testing.T) {
	env := newEnv(t)
	env.call(ipc.OpConnect, nil)
	env.call(ipc.OpDisconnect, nil)
	if strings.Join(env.tunnel.calls, ",") != "connect,disconnect" {
		t.Errorf("calls %v", env.tunnel.calls)
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
	if len(evs) != 2 || evs[0].Event != ipc.EventStatus || evs[1].Event != ipc.EventProfiles {
		t.Fatalf("greeting %v", evs)
	}
	// An empty list is [], never null: the window iterates it.
	if !strings.Contains(string(evs[1].Data), `"profiles":[]`) {
		t.Errorf("profiles %s", evs[1].Data)
	}
}
