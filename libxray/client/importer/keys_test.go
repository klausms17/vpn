package importer

import (
	"encoding/json"
	"strings"
	"testing"
)

const realityKey = "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome&flow=xtls-rprx-vision#Германия"

func TestKeysFromLinks(t *testing.T) {
	keys, skipped, err := Keys("Ключ: " + realityKey + "\nи ещё bogus://x")
	if err != nil {
		t.Fatal(err)
	}
	if len(keys) != 1 || len(skipped) != 1 {
		t.Fatalf("keys %d, skipped %q", len(keys), skipped)
	}
	k := keys[0]
	if k.Name != "Германия" || k.Protocol != "vless" || k.Address != "vpn.example.com" || k.Port != 443 || k.Security != "reality" || k.Link != realityKey {
		t.Errorf("key %+v", k)
	}
	var obs []map[string]any
	if err := json.Unmarshal(k.Outbounds, &obs); err != nil || len(obs) == 0 || obs[0]["tag"] != "proxy" {
		t.Errorf("outbounds %s (%v)", k.Outbounds, err)
	}
	// The error never quotes the link.
	if strings.Contains(skipped[0], "bogus://x") {
		t.Errorf("skipped %q", skipped)
	}
}

func TestKeysFromAPastedSubscriptionBody(t *testing.T) {
	keys, _, err := Keys(realityKey + "\n")
	if err != nil || len(keys) != 1 {
		t.Fatalf("%d keys, %v", len(keys), err)
	}
	// Not a single link: no keys, so the text is read as a body.
	if _, _, err := Keys("просто текст"); err == nil {
		t.Error("plain text gave no error")
	}
}

func TestSummary(t *testing.T) {
	for _, c := range []struct {
		added, ready int
		skipped      []string
		want         string
	}{
		{2, 2, nil, "Добавлено серверов: 2"},
		{1, 1, []string{"a", "b"}, "Добавлено: 1, пропущено: 2 (a)"},
		{0, 1, nil, "Эти ключи уже добавлены"},
		{0, 0, []string{"плохой ключ"}, "плохой ключ"},
		{0, 0, nil, "Не найдено ни одного ключа"},
	} {
		if got := Summary(c.added, c.ready, c.skipped); got != c.want {
			t.Errorf("%+v: %q", c, got)
		}
	}
}
