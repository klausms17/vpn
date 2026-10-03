// Package account is the apps' side of the accounts service
// (server/remnawave/klaus-accounts.py, docs/accounts/PLAN.md): its API and
// the state an app keeps. Requests go through an injected Do, so the
// package needs no core: Windows sends them directly or through its
// tunnel, iPhone and Mac through their own transports.
package account

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

// Status is where an account stands. The service never reports
// Unconfirmed: an app keeps it itself between a registration and a sign-in.
type Status string

const (
	SignedOut   Status = ""
	Unconfirmed Status = "unconfirmed"
	Pending     Status = "pending"
	Active      Status = "active"
	Rejected    Status = "rejected"
)

// Account is what the service says about the account signed in.
type Account struct {
	Email  string `json:"email"`
	Status Status `json:"status"`
	// SubscriptionURL is the account's subscription link, once active.
	SubscriptionURL string `json:"subscriptionUrl,omitempty"`
}

// Request is one call to the service.
type Request struct {
	Method, URL string
	// Body is JSON, or nil.
	Body []byte
	// Token is the session token, or "".
	Token string
}

// Response is the service's answer.
type Response struct {
	Status int
	Body   []byte
}

// Do sends a request and returns the answer, whatever its status; an
// error means the service did not answer. It must bound the body by
// MaxBody.
type Do func(ctx context.Context, r Request) (Response, error)

// MaxBody bounds an answer of the service.
const MaxBody = 64 * 1024

// maxMessage bounds the service's text shown to the user.
const maxMessage = 300

// ErrUnreachable means the service did not answer.
var ErrUnreachable = errors.New("Сервер аккаунтов не отвечает. Проверьте интернет и попробуйте ещё раз.")

// Error is the service's refusal.
type Error struct {
	Status int
	// Code is the service's code for it ("bad_login", "too_often", …).
	Code string
	// Message is its Russian text for the user.
	Message string
	// RetryAfter is how long to wait, for codes that say so.
	RetryAfter time.Duration
}

func (e *Error) Error() string { return e.Message }

// IsSignedOut tells whether err means the session is gone (signed out
// elsewhere, a password reset, the account deleted): the app forgets it.
func IsSignedOut(err error) bool {
	var e *Error
	return errors.As(err, &e) && e.Code == "signed_out"
}

// IsUnconfirmed tells whether a sign-in waits for the email to be
// confirmed.
func IsUnconfirmed(err error) bool {
	var e *Error
	return errors.As(err, &e) && e.Code == "unconfirmed"
}

// Client talks to one accounts service.
type Client struct {
	// Base is the service's address, such as https://sub.example.
	Base string
	// Device names this device in the account's sessions ("Windows 11").
	Device string
	Do     Do
}

// Register signs an address up; the service then mails a confirmation
// link to it. The answer is the same whether the address was free.
func (c *Client) Register(ctx context.Context, email, password string) error {
	return c.call(ctx, "POST", "register", map[string]string{"email": email, "password": password}, "", nil)
}

// Resend mails the confirmation link again.
func (c *Client) Resend(ctx context.Context, email string) error {
	return c.call(ctx, "POST", "resend", map[string]string{"email": email}, "", nil)
}

// Forgot mails a link to set a new password.
func (c *Client) Forgot(ctx context.Context, email string) error {
	return c.call(ctx, "POST", "forgot", map[string]string{"email": email}, "", nil)
}

// Login signs this device in and returns its session token.
func (c *Client) Login(ctx context.Context, email, password string) (string, Account, error) {
	var out struct {
		Token   string  `json:"token"`
		Account Account `json:"account"`
	}
	err := c.call(ctx, "POST", "login", map[string]string{"email": email, "password": password, "device": c.Device}, "", &out)
	if err == nil && (out.Token == "" || !out.Account.valid()) {
		err = errAnswer
	}
	return out.Token, out.Account, err
}

// Me returns the account a session belongs to.
func (c *Client) Me(ctx context.Context, token string) (Account, error) {
	var out struct {
		Account Account `json:"account"`
	}
	err := c.call(ctx, "GET", "me", nil, token, &out)
	if err == nil && !out.Account.valid() {
		err = errAnswer
	}
	return out.Account, err
}

// Logout ends the session.
func (c *Client) Logout(ctx context.Context, token string) error {
	return c.call(ctx, "POST", "logout", nil, token, nil)
}

// Delete deletes the account; the password is asked again.
func (c *Client) Delete(ctx context.Context, token, password string) error {
	return c.call(ctx, "POST", "delete", map[string]string{"password": password}, token, nil)
}

var errAnswer = errors.New("Сервер аккаунтов ответил непонятно. Попробуйте позже.")

func (a Account) valid() bool {
	switch a.Status {
	case Pending, Rejected:
		return a.Email != ""
	case Active:
		return a.Email != "" && strings.HasPrefix(a.SubscriptionURL, "https://")
	}
	return false
}

func (c *Client) call(ctx context.Context, method, name string, body any, token string, out any) error {
	req := Request{Method: method, URL: strings.TrimRight(c.Base, "/") + "/account/v1/" + name, Token: token}
	if body != nil {
		req.Body, _ = json.Marshal(body)
	}
	resp, err := c.Do(ctx, req)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return ErrUnreachable
	}
	if resp.Status >= 200 && resp.Status < 300 {
		if out == nil {
			return nil
		}
		if json.Unmarshal(resp.Body, out) != nil {
			return errAnswer
		}
		return nil
	}
	return refusal(resp)
}

func refusal(resp Response) error {
	var body struct {
		Error      string `json:"error"`
		Code       string `json:"code"`
		RetryAfter int    `json:"retryAfter"`
	}
	e := &Error{Status: resp.Status}
	if json.Unmarshal(resp.Body, &body) == nil && body.Error != "" {
		e.Code, e.Message = clip(body.Code, 40), clip(body.Error, maxMessage)
		if body.RetryAfter > 0 && body.RetryAfter <= 86400 {
			e.RetryAfter = time.Duration(body.RetryAfter) * time.Second
		}
		return e
	}
	// Not the service's own answer (a proxy's error page, a server error).
	e.Message = fmt.Sprintf("Сервер аккаунтов не смог ответить (ошибка %d). Попробуйте позже.", resp.Status)
	return e
}

// clip bounds a text from the service and keeps only printable characters.
func clip(s string, n int) string {
	s = strings.Map(func(r rune) rune {
		if r == '\n' || r < 0x20 || r == 0x7f || r == utf8.RuneError {
			return -1
		}
		return r
	}, s)
	if utf8.RuneCountInString(s) > n {
		s = string([]rune(s)[:n])
	}
	return s
}
