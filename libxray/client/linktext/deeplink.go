package linktext

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	deepLinkScheme = "klausvpn://"
	// maxDeepLink bounds the key or link a deep link may carry.
	maxDeepLink = 64 * 1024
)

var httpStart = regexp.MustCompile(`(?i)^https?://`)

// DeepLink returns the key or subscription link inside an "Add to Kirov
// VPN" link, or "" when there is none:
//   - klausvpn://add/<link> and klausvpn://import/<link>: the raw text
//     after the prefix, encoded or not;
//   - klausvpn://install-config?url=<link>[&name=…] (v2rayNG style), where
//     the link may be unencoded and runs to "&name=" or the end.
//
// What it returns still needs the user's confirmation before it is added.
func DeepLink(data string) string {
	link := strings.TrimSpace(data)
	if len(link) < len(deepLinkScheme) || !strings.EqualFold(link[:len(deepLinkScheme)], deepLinkScheme) {
		return ""
	}
	rest := link[len(deepLinkScheme):]
	host := rest[:strings.IndexFunc(rest+"/", func(r rune) bool { return r == '/' || r == '?' || r == '#' })]
	tail := rest[len(host):]
	var raw string
	var ok bool
	switch strings.ToLower(host) {
	case "add", "import":
		if strings.HasPrefix(tail, "/") {
			raw, ok = tail[1:], true
		} else {
			raw, ok = queryURL(tail)
		}
	case "install-config":
		raw, ok = queryURL(tail)
	}
	if !ok {
		return ""
	}
	// Decoded once, only when it is still encoded: "vless://…#My%20server"
	// must reach the parser as it is.
	text := raw
	if !strings.Contains(raw, "://") {
		text = percentDecode(raw)
	}
	text = unfoldSlash(strings.TrimSpace(text))
	// A subscription page may add "#name"; for a key it is the server's name.
	if httpStart.MatchString(text) {
		text, _, _ = strings.Cut(text, "#")
	}
	// Only a link or key goes on to the confirmation: no control
	// characters, and a scheme at the very start. Never another deep link:
	// decoded again, it would carry a link the user was not shown.
	if strings.ContainsFunc(text, unicode.IsControl) || !linkStart.MatchString(text) ||
		strings.EqualFold(text[:min(len(text), len(deepLinkScheme))], deepLinkScheme) {
		return ""
	}
	if text == "" || utf8.RuneCountInString(text) > maxDeepLink {
		return ""
	}
	return text
}

// URLHost returns the host of an http(s) URL, to show where a
// subscription comes from, or "".
func URLHost(url string) string {
	t := strings.TrimSpace(url)
	if !httpStart.MatchString(t) {
		return ""
	}
	_, after, _ := strings.Cut(t, "://")
	authority := after[:strings.IndexFunc(after+"/", func(r rune) bool { return r == '/' || r == '?' || r == '#' || unicode.IsSpace(r) })]
	if i := strings.LastIndexByte(authority, '@'); i >= 0 {
		authority = authority[i+1:]
	}
	if strings.HasPrefix(authority, "[") {
		v6, _, _ := strings.Cut(authority, "]")
		return v6 + "]"
	}
	host, _, _ := strings.Cut(authority, ":")
	return host
}

// queryURL returns the text after "url=" in "?…": to "&name=" or the end,
// since the link may carry its own "&".
func queryURL(tail string) (string, bool) {
	query, found := strings.CutPrefix(tail, "?")
	if !found {
		return "", false
	}
	var value string
	if v, ok := strings.CutPrefix(query, "url="); ok {
		value = v
	} else if _, v, ok := strings.Cut(query, "&url="); ok {
		value = v
	} else {
		return "", false
	}
	value, _, _ = strings.Cut(value, "&name=")
	return value, true
}

// unfoldSlash repairs "https:/host", which some browsers make of "//"
// inside a path.
func unfoldSlash(text string) string {
	for _, scheme := range []string{"https:/", "http:/"} {
		if len(text) > len(scheme) && strings.EqualFold(text[:len(scheme)], scheme) && text[len(scheme)] != '/' {
			return text[:len(scheme)] + "/" + text[len(scheme):]
		}
	}
	return text
}

// percentDecode decodes %XX sequences as UTF-8 and leaves broken ones as
// they are; "+" stays "+" (a path, not a form).
func percentDecode(s string) string {
	if !strings.Contains(s, "%") {
		return s
	}
	var out []byte
	for i := 0; i < len(s); i++ {
		if s[i] == '%' && i+2 < len(s) {
			if hi, lo := unhex(s[i+1]), unhex(s[i+2]); hi >= 0 && lo >= 0 {
				out = append(out, byte(hi<<4|lo))
				i += 2
				continue
			}
		}
		out = append(out, s[i])
	}
	return strings.ToValidUTF8(string(out), "�")
}

func unhex(c byte) int {
	switch {
	case '0' <= c && c <= '9':
		return int(c - '0')
	case 'a' <= c && c <= 'f':
		return int(c-'a') + 10
	case 'A' <= c && c <= 'F':
		return int(c-'A') + 10
	}
	return -1
}
