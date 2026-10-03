package libxray

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRequestReturnsEveryAnswer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		switch r.URL.Path {
		case "/echo":
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(r.Method + " " + r.Header.Get("Content-Type") + " " + r.Header.Get("Authorization") + " " +
				r.Header.Get("User-Agent") + " " + string(body)))
		case "/refuse":
			w.WriteHeader(429)
			w.Write([]byte(`{"error":"Слишком часто.","code":"too_often","retryAfter":120}`))
		case "/huge":
			w.Write([]byte(strings.Repeat("x", maxReplyBytes+1)))
		}
	}))
	defer srv.Close()

	reply, err := Request("POST", srv.URL+"/echo", "KlausVPN/1.0.1 (Windows)", `{"Authorization":"Bearer tok"}`,
		[]byte(`{"email":"ivan@mail.ru"}`), 5000, "")
	if err != nil || reply.Status != 200 ||
		string(reply.Body) != `POST application/json Bearer tok KlausVPN/1.0.1 (Windows) {"email":"ivan@mail.ru"}` {
		t.Fatalf("echo: %+v %v", reply, err)
	}
	reply, err = Request("GET", srv.URL+"/echo", "", "", nil, 5000, "")
	if err != nil || string(reply.Body) != "GET   Go-http-client/1.1 " {
		t.Fatalf("a GET without a body: %q %v", reply.Body, err)
	}
	reply, err = Request("POST", srv.URL+"/refuse", "", "", []byte(`{}`), 5000, "")
	if err != nil || reply.Status != 429 || !strings.Contains(string(reply.Body), "too_often") {
		t.Fatalf("a refusal is an answer: %+v %v", reply, err)
	}
	if _, err := Request("GET", srv.URL+"/huge", "", "", nil, 5000, ""); err == nil {
		t.Error("an answer past the limit")
	}
	if _, err := Request("DELETE", srv.URL+"/echo", "", "", nil, 5000, ""); err == nil {
		t.Error("only GET and POST")
	}
}

func TestRequestErrorsHoldNoLinkOrSecret(t *testing.T) {
	_, err := Request("POST", "http://127.0.0.1:1/account/v1/login?t=secret-token", "", `{"Authorization":"Bearer tok-secret"}`,
		[]byte(`{"password":"hunter22"}`), 2000, "")
	if err == nil {
		t.Fatal("no error for a closed port")
	}
	for _, secret := range []string{"secret-token", "tok-secret", "hunter22", "/account"} {
		if strings.Contains(err.Error(), secret) {
			t.Errorf("error %q holds %q", err, secret)
		}
	}
	if _, err := Request("POST", "::not a url", "", "", nil, 1000, ""); err == nil || err.Error() != "invalid link" {
		t.Errorf("bad link: %v", err)
	}
}
