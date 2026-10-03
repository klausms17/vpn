package account

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"
)

// fakeService answers each call by its last path segment.
type fakeService struct {
	answers map[string]Response
	seen    []Request
	err     error
}

func (f *fakeService) do(_ context.Context, r Request) (Response, error) {
	f.seen = append(f.seen, r)
	if f.err != nil {
		return Response{}, f.err
	}
	name := r.URL[strings.LastIndex(r.URL, "/")+1:]
	if a, ok := f.answers[name]; ok {
		return a, nil
	}
	return Response{Status: 404, Body: []byte(`{"error":"Нет такого запроса.","code":"not_found"}`)}, nil
}

func client(f *fakeService) *Client {
	return &Client{Base: "https://sub.example/", Device: "Windows 11", Do: f.do}
}

func answer(status int, body string) Response { return Response{Status: status, Body: []byte(body)} }

func TestRequestsAsTheServiceTakesThem(t *testing.T) {
	f := &fakeService{answers: map[string]Response{
		"register": answer(202, `{"ok":true}`),
		"login":    answer(200, `{"token":"tok","account":{"email":"ivan@mail.ru","status":"pending"}}`),
		"me":       answer(200, `{"account":{"email":"ivan@mail.ru","status":"active","subscriptionUrl":"https://sub.example/abc"}}`),
		"logout":   answer(200, `{"ok":true}`),
		"delete":   answer(200, `{"ok":true}`),
	}}
	c, ctx := client(f), context.Background()
	if err := c.Register(ctx, "ivan@mail.ru", "correct horse"); err != nil {
		t.Fatal(err)
	}
	token, acc, err := c.Login(ctx, "ivan@mail.ru", "correct horse")
	if err != nil || token != "tok" || acc != (Account{Email: "ivan@mail.ru", Status: Pending}) {
		t.Fatalf("login: %q %+v %v", token, acc, err)
	}
	acc, err = c.Me(ctx, token)
	if err != nil || acc.Status != Active || acc.SubscriptionURL != "https://sub.example/abc" {
		t.Fatalf("me: %+v %v", acc, err)
	}
	if err := c.Logout(ctx, token); err != nil {
		t.Fatal(err)
	}
	if err := c.Delete(ctx, token, "correct horse"); err != nil {
		t.Fatal(err)
	}

	want := []struct{ method, url, body, token string }{
		{"POST", "https://sub.example/account/v1/register", `{"email":"ivan@mail.ru","password":"correct horse"}`, ""},
		{"POST", "https://sub.example/account/v1/login", `{"device":"Windows 11","email":"ivan@mail.ru","password":"correct horse"}`, ""},
		{"GET", "https://sub.example/account/v1/me", "", "tok"},
		{"POST", "https://sub.example/account/v1/logout", "", "tok"},
		{"POST", "https://sub.example/account/v1/delete", `{"password":"correct horse"}`, "tok"},
	}
	for i, w := range want {
		r := f.seen[i]
		if r.Method != w.method || r.URL != w.url || string(r.Body) != w.body || r.Token != w.token {
			t.Errorf("request %d: %s %s %s %q", i, r.Method, r.URL, r.Body, r.Token)
		}
	}
}

func TestRefusalsCarryTheServiceText(t *testing.T) {
	f := &fakeService{answers: map[string]Response{
		"register": answer(429, `{"error":"Слишком часто. Следующее письмо можно отправить через 2 минуты.","code":"too_often","retryAfter":120}`),
		"login":    answer(403, `{"error":"Сначала подтвердите почту: письмо отправлено на ivan@mail.ru.","code":"unconfirmed"}`),
		"me":       answer(401, `{"error":"Вы вышли из аккаунта. Войдите снова.","code":"signed_out"}`),
		"forgot":   answer(502, `<html><title>502 Bad Gateway</title></html>`),
		"resend":   answer(400, `{"error":"Плохо\nи\u0007 длинно `+strings.Repeat("я", 400)+`","code":"x"}`),
	}}
	c, ctx := client(f), context.Background()
	err := c.Register(ctx, "ivan@mail.ru", "correct horse")
	var e *Error
	if !errors.As(err, &e) || e.Code != "too_often" || e.RetryAfter != 2*time.Minute || !strings.HasPrefix(e.Error(), "Слишком часто") {
		t.Errorf("register: %#v", err)
	}
	if _, _, err := c.Login(ctx, "ivan@mail.ru", "x"); !IsUnconfirmed(err) || IsSignedOut(err) {
		t.Errorf("login: %v", err)
	}
	if _, err := c.Me(ctx, "tok"); !IsSignedOut(err) {
		t.Errorf("me: %v", err)
	}
	if err := c.Forgot(ctx, "ivan@mail.ru"); err == nil || err.Error() != "Сервер аккаунтов не смог ответить (ошибка 502). Попробуйте позже." {
		t.Errorf("forgot: %v", err)
	}
	err = c.Resend(ctx, "ivan@mail.ru")
	if strings.ContainsAny(err.Error(), "\n\a") || len([]rune(err.Error())) != maxMessage {
		t.Errorf("resend: %q", err)
	}
}

func TestAnswersThatMakeNoSense(t *testing.T) {
	for _, body := range []string{
		`not json`,
		`{"token":"","account":{"email":"ivan@mail.ru","status":"pending"}}`,
		`{"token":"tok","account":{"email":"ivan@mail.ru","status":"maybe"}}`,
		`{"token":"tok","account":{"email":"ivan@mail.ru","status":"active"}}`,
		`{"token":"tok","account":{"email":"ivan@mail.ru","status":"active","subscriptionUrl":"http://plain"}}`,
	} {
		f := &fakeService{answers: map[string]Response{"login": answer(200, body)}}
		if _, _, err := client(f).Login(context.Background(), "ivan@mail.ru", "x"); err != errAnswer {
			t.Errorf("%s: %v", body, err)
		}
	}
}

func TestUnreachable(t *testing.T) {
	f := &fakeService{err: errors.New("dial tcp: i/o timeout")}
	if err := client(f).Register(context.Background(), "ivan@mail.ru", "x"); err != ErrUnreachable {
		t.Errorf("%v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := client(f).Register(ctx, "ivan@mail.ru", "x"); err != context.Canceled {
		t.Errorf("cancelled: %v", err)
	}
}

func TestStateIsDue(t *testing.T) {
	const min = int64(60_000)
	s := State{Email: "ivan@mail.ru", Token: "tok", Status: Pending, CheckedAt: 1000 * min}
	if s.Due(1004*min) || !s.Due(1005*min) {
		t.Error("pending: every 5 minutes")
	}
	s.Status = Active
	if !s.Due(1005 * min) {
		t.Error("active without its subscription here: every 5 minutes")
	}
	s.SubscriptionID = "sub1"
	if s.Due(1059*min) || !s.Due(1060*min) {
		t.Error("active: every hour")
	}
	if !s.Due(999 * min) {
		t.Error("a clock set back makes it due")
	}
	if (State{Status: Pending}).Due(1 << 50) {
		t.Error("signed out is never due")
	}
	s = s.With(Account{Email: "ivan@mail.ru", Status: Rejected}, 2000*min)
	if s.Status != Rejected || s.CheckedAt != 2000*min || s.Token != "tok" {
		t.Errorf("with: %+v", s)
	}
	b, _ := json.Marshal(State{})
	if string(b) != "{}" {
		t.Errorf("empty state: %s", b)
	}
}
