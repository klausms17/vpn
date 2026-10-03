package account

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"mime/quotedprintable"
	"net"
	"net/http"
	"net/http/httptest"
	"net/mail"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// The client against the real service (server/remnawave/klaus-accounts.py)
// with a mail sink and a fake panel: the two sides must agree on every
// request and answer.
func TestAgainstTheRealService(t *testing.T) {
	python, err := exec.LookPath("python3")
	if err != nil || runtime.GOOS == "windows" {
		t.Skip("needs python3 (the service runs on Linux)")
	}
	script, _ := filepath.Abs("../../../server/remnawave/klaus-accounts.py")
	if _, err := os.Stat(script); err != nil {
		t.Skip("no klaus-accounts.py next to libxray")
	}
	mail := startMailSink(t)
	panel := startPanel(t)
	port := freePort(t)
	base := fmt.Sprintf("http://127.0.0.1:%d", port)
	env := append(os.Environ(),
		"DATA_DIR="+t.TempDir(), fmt.Sprintf("LISTEN_PORT=%d", port), "PUBLIC_URL="+base,
		"SMTP_HOST=127.0.0.1", fmt.Sprintf("SMTP_PORT=%d", mail.port), "SMTP_TLS=none",
		"SMTP_FROM=Kirov VPN <kirov@example.com>", "MAIL_GAP=0", "PANEL_URL="+panel.URL, "PANEL_TOKEN=token", "PANEL_SQUAD=squad",
		"TELEGRAM_BOT_TOKEN=", "PYTHONDONTWRITEBYTECODE=1")
	service := exec.Command(python, "-u", script, "serve")
	service.Env = env
	service.Stdout, service.Stderr = io.Discard, io.Discard
	if err := service.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { service.Process.Kill(); service.Wait() })
	waitUp(t, base+"/account/health")

	c := &Client{Base: base, Device: "Test PC", Do: httpDo}
	ctx := context.Background()
	if err := c.Register(ctx, "Ivan@Mail.Example", "correct horse"); err != nil {
		t.Fatal(err)
	}
	confirm := mail.link(t, "ivan@mail.example", "/account/confirm")
	if _, _, err := c.Login(ctx, "ivan@mail.example", "correct horse"); !IsUnconfirmed(err) {
		t.Fatalf("before the confirmation: %v", err)
	}
	postForm(t, base+"/account/confirm", url.Values{"t": {confirm}}, "Почта подтверждена")
	token, acc, err := c.Login(ctx, "ivan@mail.example", "correct horse")
	if err != nil || acc.Status != Pending {
		t.Fatalf("login: %+v %v", acc, err)
	}

	owner := exec.Command(python, script, "approve", "ivan@mail.example")
	owner.Env = env
	if out, err := owner.CombinedOutput(); err != nil || !strings.HasPrefix(string(out), "Доступ выдан: ivan@mail.example") {
		t.Fatalf("approve: %s %v", out, err)
	}
	acc, err = c.Me(ctx, token)
	if err != nil || acc.Status != Active || acc.SubscriptionURL != "https://sub.example/short7" {
		t.Fatalf("me: %+v %v", acc, err)
	}

	if err := c.Forgot(ctx, "ivan@mail.example"); err != nil {
		t.Fatal(err)
	}
	reset := mail.link(t, "ivan@mail.example", "/account/reset")
	postForm(t, base+"/account/reset", url.Values{"t": {reset}, "password": {"brand new pass"}, "again": {"brand new pass"}},
		"Пароль изменён")
	if _, err := c.Me(ctx, token); !IsSignedOut(err) {
		t.Fatalf("after a reset: %v", err)
	}
	var e *Error
	if _, _, err := c.Login(ctx, "ivan@mail.example", "correct horse"); !errors.As(err, &e) || e.Code != "bad_login" {
		t.Fatalf("old password: %v", err)
	}
	if token, _, err = c.Login(ctx, "ivan@mail.example", "brand new pass"); err != nil {
		t.Fatal(err)
	}
	// Five letters a day to one address: the sign-up's, the reset's and
	// three of these (the access letter came from the owner's command).
	for i := 0; i < 3; i++ {
		if err := c.Resend(ctx, "ivan@mail.example"); err != nil {
			t.Fatal(err)
		}
	}
	if err := c.Resend(ctx, "ivan@mail.example"); !errors.As(err, &e) || e.Code != "too_often" || e.RetryAfter <= 0 {
		t.Fatalf("the sixth letter of the day: %v", err)
	}
	if err := c.Delete(ctx, token, "brand new pass"); err != nil {
		t.Fatal(err)
	}
	if panel.deleted() != "7" {
		t.Errorf("the panel user was not deleted: %q", panel.deleted())
	}
	if _, err := c.Me(ctx, token); !IsSignedOut(err) {
		t.Errorf("after deleting: %v", err)
	}
}

func httpDo(ctx context.Context, r Request) (Response, error) {
	req, err := http.NewRequestWithContext(ctx, r.Method, r.URL, strings.NewReader(string(r.Body)))
	if err != nil {
		return Response{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	if r.Token != "" {
		req.Header.Set("Authorization", "Bearer "+r.Token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return Response{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, MaxBody))
	return Response{Status: resp.StatusCode, Body: body}, err
}

func postForm(t *testing.T, target string, form url.Values, want string) {
	t.Helper()
	resp, err := http.PostForm(target, form)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	page, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(page), want) {
		t.Fatalf("%s: no %q in\n%s", target, want, page)
	}
}

func freePort(t *testing.T) int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func waitUp(t *testing.T, health string) {
	for i := 0; i < 100; i++ {
		if resp, err := http.Get(health); err == nil {
			resp.Body.Close()
			if resp.StatusCode == 200 {
				return
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("the service did not start")
}

// fakePanel answers the user calls the service makes.
type fakePanel struct {
	*httptest.Server
	mu   sync.Mutex
	gone string
}

func (p *fakePanel) deleted() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.gone
}

func startPanel(t *testing.T) *fakePanel {
	p := &fakePanel{}
	p.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer token" {
			w.WriteHeader(401)
			return
		}
		switch {
		case r.Method == "POST" && r.URL.Path == "/api/users":
			var body map[string]any
			json.NewDecoder(r.Body).Decode(&body)
			if body["description"] != "klaus-accounts" || body["expireAt"] != "2099-12-31T00:00:00.000Z" {
				w.WriteHeader(400)
				return
			}
			w.WriteHeader(201)
			fmt.Fprintf(w, `{"response":{"id":7,"username":%q,"subscriptionUrl":"https://sub.example/short7"}}`, body["username"])
		case r.Method == "DELETE" && strings.HasPrefix(r.URL.Path, "/api/users/"):
			p.mu.Lock()
			p.gone = strings.TrimPrefix(r.URL.Path, "/api/users/")
			p.mu.Unlock()
			// As Remnawave answers a deletion: 200 and no body.
		default:
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(p.Close)
	return p
}

// mailSink is just enough SMTP for Python's smtplib, keeping the letters.
type mailSink struct {
	port    int
	mu      sync.Mutex
	letters []string
}

var linkRe = regexp.MustCompile(`http://127\.0\.0\.1:\d+(/account/[a-z]+)\?t=([A-Za-z0-9_-]+)`)

// link waits for a letter to address with a link to path and returns
// its token.
func (m *mailSink) link(t *testing.T, address, path string) string {
	t.Helper()
	for i := 0; i < 100; i++ {
		m.mu.Lock()
		for j := len(m.letters) - 1; j >= 0; j-- {
			if !strings.Contains(m.letters[j], "To: "+address) {
				continue
			}
			if found := linkRe.FindStringSubmatch(m.letters[j]); found != nil && found[1] == path {
				m.letters = append(m.letters[:j], m.letters[j+1:]...)
				m.mu.Unlock()
				return found[2]
			}
		}
		m.mu.Unlock()
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("no letter to %s with %s", address, path)
	return ""
}

func startMailSink(t *testing.T) *mailSink {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	m := &mailSink{port: l.Addr().(*net.TCPAddr).Port}
	go func() {
		for {
			conn, err := l.Accept()
			if err != nil {
				return
			}
			go m.serve(conn)
		}
	}()
	return m
}

func (m *mailSink) serve(conn net.Conn) {
	defer conn.Close()
	r := bufio.NewReader(conn)
	say := func(s string) { fmt.Fprintf(conn, "%s\r\n", s) }
	say("220 sink")
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		switch verb := strings.ToUpper(strings.Fields(line + " x")[0]); verb {
		case "EHLO", "HELO":
			say("250 sink")
		case "DATA":
			say("354 go on")
			var b strings.Builder
			for {
				l, err := r.ReadString('\n')
				if err != nil || l == ".\r\n" {
					break
				}
				b.WriteString(l)
			}
			m.mu.Lock()
			m.letters = append(m.letters, decode(b.String()))
			m.mu.Unlock()
			say("250 queued")
		case "QUIT":
			say("221 bye")
			return
		default:
			say("250 ok")
		}
	}
}

// decode returns a letter's To header and its parts' text, decoded.
func decode(raw string) string {
	msg, err := mail.ReadMessage(strings.NewReader(raw))
	if err != nil {
		return raw
	}
	out := "To: " + msg.Header.Get("To") + "\n"
	_, params, _ := mime.ParseMediaType(msg.Header.Get("Content-Type"))
	parts := multipart.NewReader(msg.Body, params["boundary"])
	for {
		p, err := parts.NextRawPart()
		if err != nil {
			return out
		}
		var body io.Reader = p
		switch strings.ToLower(p.Header.Get("Content-Transfer-Encoding")) {
		case "base64":
			body = base64.NewDecoder(base64.StdEncoding, p)
		case "quoted-printable":
			body = quotedprintable.NewReader(p)
		}
		text, _ := io.ReadAll(body)
		out += string(text) + "\n"
	}
}
