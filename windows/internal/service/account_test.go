package service

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/klausms17/vpn/libxray/client/account"
	"github.com/klausms17/vpn/libxray/client/store"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// accountsService is a fake accounts service: one account, whose state the
// test sets, as klaus-accounts.py answers.
type accountsService struct {
	mu        sync.Mutex
	status    string // "unconfirmed", "pending", "active", "rejected"
	link      string
	password  string
	sessions  map[string]bool
	calls     []string
	down      bool
	hold      chan struct{} // set: each call waits for it
	nextToken int
}

func (f *accountsService) do(_ context.Context, r account.Request) (account.Response, error) {
	if f.hold != nil {
		<-f.hold
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.down {
		return account.Response{}, errors.New("dial tcp: i/o timeout")
	}
	name := r.URL[strings.LastIndex(r.URL, "/")+1:]
	f.calls = append(f.calls, name)
	var body map[string]string
	json.Unmarshal(r.Body, &body)
	answer := func(status int, v any) (account.Response, error) {
		b, _ := json.Marshal(v)
		return account.Response{Status: status, Body: b}, nil
	}
	refuse := func(status int, code, text string) (account.Response, error) {
		return answer(status, map[string]string{"code": code, "error": text})
	}
	me := map[string]any{"email": "ivan@mail.ru", "status": f.status}
	if f.status == "active" {
		me["subscriptionUrl"] = f.link
	}
	switch name {
	case "register":
		f.status, f.password = "unconfirmed", body["password"]
		return answer(202, map[string]bool{"ok": true})
	case "resend", "forgot":
		return answer(202, map[string]bool{"ok": true})
	case "login":
		if body["password"] != f.password || body["email"] != "ivan@mail.ru" {
			return refuse(401, "bad_login", "Неверная почта или пароль.")
		}
		if f.status == "unconfirmed" {
			return refuse(403, "unconfirmed", "Сначала подтвердите почту: письмо отправлено на ivan@mail.ru.")
		}
		f.nextToken++
		token := strings.Repeat("t", 42) + string(rune('0'+f.nextToken))
		f.sessions[token] = true
		return answer(200, map[string]any{"token": token, "account": me})
	}
	if !f.sessions[r.Token] {
		return refuse(401, "signed_out", "Вы вышли из аккаунта. Войдите снова.")
	}
	switch name {
	case "me":
		return answer(200, map[string]any{"account": me})
	case "logout":
		delete(f.sessions, r.Token)
		return answer(200, map[string]bool{"ok": true})
	case "delete":
		if body["password"] != f.password {
			return refuse(401, "bad_login", "Неверный пароль.")
		}
		f.sessions = map[string]bool{}
		return answer(200, map[string]bool{"ok": true})
	}
	return refuse(404, "not_found", "Нет такого запроса.")
}

func (f *accountsService) set(status, link string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.status, f.link = status, link
}

type accountEnv struct {
	*testEnv
	svc   *accountsService
	clock int64
	state *store.Store[account.State]
}

func newAccountEnv(t *testing.T) *accountEnv {
	env := &accountEnv{testEnv: newEnv(t), svc: &accountsService{sessions: map[string]bool{}}, clock: 1_000_000}
	env.state = store.New(filepath.Join(t.TempDir(), "account.json"), func() account.State { return account.State{} }, nil, func(string) {})
	client := &account.Client{Base: "https://sub.example", Device: "Windows 11", Do: env.svc.do}
	env.h.accounts = newAccounts(env.h, client, env.state, func() int64 { return env.clock })
	return env
}

// shown is the account as the newest event shows it (none: signed out).
func (env *accountEnv) shown(t *testing.T) ipc.Account {
	t.Helper()
	var a ipc.Account
	if evs := env.named(ipc.EventAccount); len(evs) > 0 {
		json.Unmarshal(evs[len(evs)-1].Data, &a)
	}
	return a
}

func (env *accountEnv) do(t *testing.T, op string, email, password string) string {
	t.Helper()
	res, err := env.call(op, ipc.AccountArgs{Email: email, Password: password})
	if err != nil {
		t.Fatalf("%s: %v", op, err)
	}
	if r, ok := res.(ipc.AccountResult); ok {
		return r.Message
	}
	return ""
}

func TestWithoutAnAccountsServiceNothingRuns(t *testing.T) {
	env := newEnv(t)
	if _, err := env.call(ipc.OpAccountLogin, ipc.AccountArgs{Email: "ivan@mail.ru", Password: "x"}); err != errNoAccounts {
		t.Errorf("login: %v", err)
	}
	env.h.accounts.checkIfDue(context.Background())
}

func TestSignUpSignInAndTheAccountsServers(t *testing.T) {
	env := newAccountEnv(t)
	env.serves("Kirov VPN", keyDE, keyNL)

	msg := env.do(t, ipc.OpAccountRegister, " Ivan@Mail.ru ", "correct horse")
	if msg != "Мы отправили вам письмо для подтверждения на ivan@mail.ru. Пожалуйста, завершите регистрацию, пройдя по ссылке из письма." {
		t.Errorf("register: %q", msg)
	}
	if a := env.shown(t); a != (ipc.Account{Available: true, Email: "ivan@mail.ru", Status: ipc.AccountUnconfirmed}) {
		t.Errorf("after the sign-up: %+v", a)
	}
	_, err := env.call(ipc.OpAccountLogin, ipc.AccountArgs{Email: "ivan@mail.ru", Password: "correct horse"})
	if err == nil || !strings.HasPrefix(err.Error(), "Сначала подтвердите почту") {
		t.Errorf("before the confirmation: %v", err)
	}

	env.svc.set("pending", "")
	if msg := env.do(t, ipc.OpAccountLogin, "ivan@mail.ru", "correct horse"); msg != "Вы вошли." {
		t.Errorf("login: %q", msg)
	}
	if a := env.shown(t); a.Status != ipc.AccountPending || a.Busy {
		t.Errorf("waiting: %+v", a)
	}
	// The owner grants access: the next check five minutes on brings the
	// servers.
	env.svc.set("active", subLink)
	env.clock += 4 * 60_000
	env.h.accounts.checkIfDue(context.Background())
	if len(env.h.profiles.Read().Subscriptions) != 0 {
		t.Fatal("checked before it was due")
	}
	env.clock += 60_000
	env.h.accounts.checkIfDue(context.Background())
	p := env.last(t)
	if len(p.Subscriptions) != 1 || len(p.Profiles) != 2 || env.shown(t).Status != ipc.AccountActive {
		t.Fatalf("after the access: %+v %+v", p, env.shown(t))
	}
	saved := env.h.profiles.Read()
	if !saved.Subscriptions[0].Account || env.state.Read().SubscriptionID != saved.Subscriptions[0].ID {
		t.Errorf("the account's subscription is not marked: %+v %+v", saved.Subscriptions[0], env.state.Read())
	}

	// The owner gives the account another link: the subscription follows.
	env.svc.set("active", subLink+"-new")
	env.clock += 61 * 60_000
	env.h.accounts.checkIfDue(context.Background())
	if subs := env.h.profiles.Read().Subscriptions; len(subs) != 1 || subs[0].URL != subLink+"-new" {
		t.Errorf("new link: %+v", subs)
	}

	if err := env.h.accounts.logout(context.Background()); err != nil {
		t.Fatal(err)
	}
	p = env.last(t)
	if len(p.Subscriptions) != 0 || len(p.Profiles) != 0 {
		t.Errorf("servers left after signing out: %+v", p)
	}
	if env.state.Read() != (account.State{}) || env.shown(t) != (ipc.Account{Available: true}) {
		t.Errorf("state after signing out: %+v", env.state.Read())
	}
	if len(env.svc.sessions) != 0 {
		t.Error("the service did not hear of the sign-out")
	}
}

func TestTheSameLinkAddedByHandIsTakenOver(t *testing.T) {
	env := newAccountEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.importText(t, subLink)
	env.svc.set("active", subLink)
	env.svc.password = "correct horse"
	env.do(t, ipc.OpAccountLogin, "ivan@mail.ru", "correct horse")
	subs := env.h.profiles.Read().Subscriptions
	if len(subs) != 1 || !subs[0].Account {
		t.Errorf("subscriptions: %+v", subs)
	}
}

func TestASessionEndedElsewhereSignsThisPCOut(t *testing.T) {
	env := newAccountEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.svc.set("active", subLink)
	env.svc.password = "correct horse"
	env.do(t, ipc.OpAccountLogin, "ivan@mail.ru", "correct horse")
	// A password reset on another device.
	env.svc.mu.Lock()
	env.svc.sessions = map[string]bool{}
	env.svc.mu.Unlock()
	if err := env.h.accounts.check(context.Background()); err != nil {
		t.Fatal(err)
	}
	if env.state.Read().SignedIn() || len(env.h.profiles.Read().Subscriptions) != 0 {
		t.Error("still signed in after the session ended")
	}
}

func TestDeletingTheAccount(t *testing.T) {
	env := newAccountEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.svc.set("active", subLink)
	env.svc.password = "correct horse"
	env.do(t, ipc.OpAccountLogin, "ivan@mail.ru", "correct horse")
	if _, err := env.call(ipc.OpAccountDelete, ipc.AccountArgs{Password: "wrong"}); err == nil || err.Error() != "Неверный пароль." {
		t.Errorf("wrong password: %v", err)
	}
	if !env.state.Read().SignedIn() {
		t.Fatal("signed out by a wrong password")
	}
	env.do(t, ipc.OpAccountDelete, "", "correct horse")
	if env.state.Read().SignedIn() || len(env.h.profiles.Read().Subscriptions) != 0 {
		t.Error("the account's servers stayed")
	}
}

func TestOneAccountRequestAtATime(t *testing.T) {
	env := newAccountEnv(t)
	env.svc.hold = make(chan struct{})
	done := make(chan error)
	go func() {
		_, err := env.call(ipc.OpAccountForgot, ipc.AccountArgs{Email: "ivan@mail.ru"})
		done <- err
	}()
	for !env.shown(t).Busy {
		// The first request has started.
	}
	if _, err := env.call(ipc.OpAccountResend, ipc.AccountArgs{Email: "ivan@mail.ru"}); err != errAccountBusy {
		t.Errorf("a second request: %v", err)
	}
	env.svc.hold <- struct{}{}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if env.shown(t).Busy {
		t.Error("still busy")
	}
}

func TestWhatAWindowSendsIsChecked(t *testing.T) {
	env := newAccountEnv(t)
	for _, a := range []ipc.AccountArgs{
		{Email: "", Password: "x"},
		{Email: strings.Repeat("a", 250) + "@mail.ru", Password: "x"},
		{Email: "ivan@mail.ru", Password: strings.Repeat("x", 1025)},
		{Email: "ivan@mail.ru", Password: ""},
	} {
		if _, err := env.call(ipc.OpAccountLogin, a); err == nil {
			t.Errorf("%+v accepted", a)
		}
	}
	if len(env.svc.calls) != 0 {
		t.Errorf("the service was asked: %v", env.svc.calls)
	}
}

func TestAccountSecretsStayInTheService(t *testing.T) {
	env := newAccountEnv(t)
	env.serves("Kirov VPN", keyDE)
	env.svc.set("active", subLink)
	env.svc.password = "correct horse"
	env.do(t, ipc.OpAccountLogin, "ivan@mail.ru", "correct horse")
	token := env.state.Read().Token
	env.mu.Lock()
	defer env.mu.Unlock()
	for _, ev := range env.events {
		for _, secret := range []string{token, "correct horse", subLink} {
			if strings.Contains(string(ev.Data), secret) {
				t.Errorf("event %s holds a secret: %s", ev.Event, ev.Data)
			}
		}
	}
	for _, l := range env.logs {
		for _, secret := range []string{token, "correct horse", subLink, "ivan"} {
			if strings.Contains(l, secret) {
				t.Errorf("log %q holds %q", l, secret)
			}
		}
	}
}
