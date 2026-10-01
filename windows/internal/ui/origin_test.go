package ui

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestOnlyTheWindowsOwnPageMayCall(t *testing.T) {
	called := 0
	h := sameOrigin(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { called++ }))
	for origin, want := range map[string]int{
		"":                                    http.StatusOK, // the page's own assets
		"http://wails.localhost":              http.StatusOK,
		"https://evil.example":                http.StatusForbidden,
		"null":                                http.StatusForbidden, // a sandboxed frame or data: page
		"http://wails.localhost.evil.example": http.StatusForbidden,
	} {
		r := httptest.NewRequest(http.MethodPost, "http://wails.localhost/wails/runtime", nil)
		if origin != "" {
			r.Header.Set("Origin", origin)
		}
		w := httptest.NewRecorder()
		before := called
		h.ServeHTTP(w, r)
		if w.Code != want || (want == http.StatusOK) != (called > before) {
			t.Errorf("origin %q: %d, called %v", origin, w.Code, called > before)
		}
	}
}
