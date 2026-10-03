package linktext

import (
	"reflect"
	"testing"
)

func TestLinks(t *testing.T) {
	for _, c := range []struct {
		text string
		want []string
	}{
		{"vless://a@h.example:443#Мой сервер 1", []string{"vless://a@h.example:443#Мой сервер 1"}},
		{"  vless://a@h:1\r\n\r\ntrojan://b@h:2\rss://c@h:3  ", []string{"vless://a@h:1", "trojan://b@h:2", "ss://c@h:3"}},
		// Picked out of a message, without the punctuation around it.
		{"Твой ключ: vless://a@h:1, и ещё (trojan://b@h:2).", []string{"vless://a@h:1", "trojan://b@h:2"}},
		{"«https://sub.example/x»", []string{"https://sub.example/x"}},
		{`{"outbounds":[]}`, nil},
		{"[1]", nil},
		{"просто текст", nil},
		{"", nil},
	} {
		if got := Links(c.text); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%q: %q, want %q", c.text, got, c.want)
		}
	}
}

func TestSubscriptionURL(t *testing.T) {
	const sub = "https://sub.example.com/abc"
	for text, want := range map[string]string{
		sub:                          sub,
		"HTTP://sub.example.com/abc": "HTTP://sub.example.com/abc",
		".https://evil.example/sub":  "https://evil.example/sub",
		"https://evil.example/sub x": "https://evil.example/sub x",
		"Моя подписка: https://evil.example/sub": "https://evil.example/sub",
		sub + "\n" + sub:                    "",
		"vless://uuid@host.example.com:443": "",
		`[{"outbounds":[]}]`:                "",
	} {
		if got := SubscriptionURL(text); got != want {
			t.Errorf("%q: %q, want %q", text, got, want)
		}
	}
}
