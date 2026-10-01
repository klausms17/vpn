package importer

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/klausms17/vpn/libxray/client/model"
)

const realityKey = "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?type=tcp&security=reality&pbk=Iv4yHdwV8Hc9BPh-c3zWJhDPLA1WZwpFNjTCn9JM2TM&sni=www.example.com&sid=ab&fp=chrome&flow=xtls-rprx-vision#Германия"

func TestKeysFromLinks(t *testing.T) {
	keys, skipped, err := Keys(context.Background(), "Ключ: "+realityKey+"\nи ещё bogus://x", nil)
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
	keys, _, err := Keys(context.Background(), realityKey+"\n", nil)
	if err != nil || len(keys) != 1 {
		t.Fatalf("%d keys, %v", len(keys), err)
	}
	// Not a single link: no keys, so the text is read as a body.
	if _, _, err := Keys(context.Background(), "просто текст", nil); err == nil {
		t.Error("plain text gave no error")
	}
}

// insecureKeys are n keys that ask to skip the certificate check, so each
// needs its certificate fetched: from a closed local port, which fails at
// once.
func insecureKeys(n int) string {
	var b strings.Builder
	for i := range n {
		fmt.Fprintf(&b, "vless://11111111-2222-3333-4444-555555555555@127.0.0.1:1?security=tls&allowInsecure=1&type=tcp#self%d\n", i)
	}
	return b.String()
}

func TestOneImportFetchesAtMostMaxPinsCertificates(t *testing.T) {
	keys, skipped, err := Keys(context.Background(), insecureKeys(maxPins+4)+realityKey, nil)
	if err != nil || len(keys) != 1 || len(skipped) != maxPins+4 {
		t.Fatalf("%d keys, skipped %d, %v", len(keys), len(skipped), err)
	}
	fetched, refused := 0, 0
	for _, s := range skipped {
		switch {
		case strings.Contains(s, "не удалось получить сертификат"):
			fetched++
		case strings.Contains(s, "слишком много ключей"):
			refused++
		}
	}
	if fetched != maxPins || refused != 4 {
		t.Errorf("fetched %d, refused %d: %q", fetched, refused, skipped)
	}
}

func TestACancelledImportFetchesNothing(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	keys, skipped, err := Keys(ctx, insecureKeys(3)+realityKey, nil)
	if err != nil || len(keys) != 1 || len(skipped) != 3 {
		t.Fatalf("%d keys, skipped %q, %v", len(keys), skipped, err)
	}
	for _, s := range skipped {
		if !strings.HasSuffix(s, "добавление прервано") {
			t.Errorf("skipped %q", s)
		}
	}
}

func TestLongNamesAreCutInMessagesToo(t *testing.T) {
	long := strings.Repeat("&", 3*model.MaxName)
	_, skipped, _ := Keys(context.Background(), "vless://11111111-2222-3333-4444-555555555555@127.0.0.1:1?security=tls&allowInsecure=1&type=tcp#"+long, nil)
	if len(skipped) != 1 || utf8.RuneCountInString(strings.SplitN(skipped[0], ":", 2)[0]) != model.MaxName {
		t.Errorf("skipped %q", skipped)
	}
}

func TestTheServiceRefusesWhatItMustNotRun(t *testing.T) {
	extra := url.QueryEscape(`{"downloadSettings":{"network":"xdrive","xdriveSettings":{"service":"local","remoteFolder":"C:\\Windows"}}}`)
	// It would need a certificate too: refused before any is fetched.
	bad := "vless://11111111-2222-3333-4444-555555555555@127.0.0.1:1?security=tls&allowInsecure=1&type=xhttp&extra=" + extra + "#bad"
	keys, skipped, err := Keys(context.Background(), bad+"\n"+realityKey, ForService)
	if err != nil || len(keys) != 1 || keys[0].Name != "Германия" || len(skipped) != 1 {
		t.Fatalf("%d keys, skipped %q, %v", len(keys), skipped, err)
	}
	if !strings.HasPrefix(skipped[0], "bad: ключ просит у ядра то, что Kirov VPN для Windows не выполняет") {
		t.Errorf("skipped %q", skipped[0])
	}
	// Elsewhere (nil check) the core decides, as on Android.
	if keys, _, _ := Keys(context.Background(), realityKey, nil); len(keys) != 1 {
		t.Errorf("%d keys without a check", len(keys))
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
