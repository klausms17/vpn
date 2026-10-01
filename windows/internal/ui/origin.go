package ui

import "net/http"

// appOrigin is where WebView2 serves the window's page on Windows.
const appOrigin = "http://wails.localhost"

// sameOrigin refuses requests from any page but the window's own. The
// window's calls to the service go through this server, and a page the
// window was led to, say by a link dropped on it, could make them too.
// Browsers name the origin of every cross-origin request, and of every
// POST, so a request that names none is the page's own.
func sameOrigin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if o := r.Header.Get("Origin"); o != "" && o != appOrigin {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}
