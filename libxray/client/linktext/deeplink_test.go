package linktext

import (
	"strings"
	"testing"
)

// The cases of the Android app's DeepLinkTest.

const sub = "https://sub.example.com/sub/Ab_c-12"

func TestDeepLinkTakesTheRawLink(t *testing.T) {
	for in, want := range map[string]string{
		"klausvpn://add/" + sub:                   sub,
		"klausvpn://add/" + sub + "?token=1&x=2":  sub + "?token=1&x=2",
		"KlausVPN://ADD/" + sub:                   sub,
		"klausvpn://import/" + sub:                sub,
		"klausvpn://import?url=" + sub:            sub,
		"  klausvpn://add/" + sub + "\n":          sub,
		"klausvpn://add/https:/sub.example.com/x": "https://sub.example.com/x",
	} {
		if got := DeepLink(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestDeepLinkDecodesOnce(t *testing.T) {
	for in, want := range map[string]string{
		"klausvpn://add/https%3A%2F%2Fsub.example.com%2Fsub%2FAb_c-12": sub,
		"klausvpn://add/https%3a%2f%2fsub.example.com%2fsub%2fAb_c-12": sub,
		// "%2541" is "%41" after one decoding, not "A".
		"klausvpn://add/https%3A%2F%2Fx.example.com%2F%2541":                    "https://x.example.com/%41",
		"klausvpn://add/https%3A%2F%2Fx.example.com%2F%D1%82%D0%B5%D1%81%D1%82": "https://x.example.com/тест",
	} {
		if got := DeepLink(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestDeepLinkDropsTheSubscriptionName(t *testing.T) {
	for _, in := range []string{
		"klausvpn://add/" + sub + "#Ivan",
		"klausvpn://add/https%3A%2F%2Fsub.example.com%2Fsub%2FAb_c-12%23Ivan",
		"klausvpn://install-config?url=" + sub + "#Ivan",
	} {
		if got := DeepLink(in); got != sub {
			t.Errorf("%q: %q, want %q", in, got, sub)
		}
	}
}

func TestDeepLinkKeyKeepsItsName(t *testing.T) {
	const key = "vless://uuid@host.example.com:443?security=reality&sni=a.com#%F0%9F%87%B3%F0%9F%87%B1%20NL"
	if got := DeepLink("klausvpn://add/" + key); got != key {
		t.Errorf("%q, want %q", got, key)
	}
}

func TestDeepLinkInstallConfig(t *testing.T) {
	for in, want := range map[string]string{
		"klausvpn://install-config?url=" + sub:              sub,
		"klausvpn://install-config?url=" + sub + "&name=Iv": sub,
		// v2rayNG order: name first.
		"klausvpn://install-config?name=Ivan&url=" + sub: sub,
		// An unencoded link with its own query.
		"klausvpn://install-config?url=" + sub + "?a=1&b=2&name=Ivan":                           sub + "?a=1&b=2",
		"klausvpn://install-config?url=https%3A%2F%2Fsub.example.com%2Fsub%2FAb_c-12&name=Ivan": sub,
	} {
		if got := DeepLink(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

func TestDeepLinkIgnoresAnythingElse(t *testing.T) {
	for _, in := range []string{
		"",
		"klausvpn://add/",
		"klausvpn://add",
		"klausvpn://install-config?name=Ivan",
		"klausvpn://settings/x",
		"https://example.com/add/x",
		"klausvpn://add/" + strings.Repeat("a", 70_000),
		// Only links and keys reach the confirmation.
		"klausvpn://add/hello",
		"klausvpn://add/https%3A%2F%2Fx.example%0A%0Dy",
	} {
		if got := DeepLink(in); got != "" {
			t.Errorf("%.60q: %q, want nothing", in, got)
		}
	}
}

func TestURLHost(t *testing.T) {
	for in, want := range map[string]string{
		sub: "sub.example.com",
		"https://user:pw@sub.example.com:8443/x?y#z": "sub.example.com",
		"http://[2001:db8::1]:8080/sub":              "[2001:db8::1]",
		"https://evil.example/sub x":                 "evil.example",
		"vless://uuid@host.example.com:443":          "",
	} {
		if got := URLHost(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}

// The dialog names the host that the import really fetches, however the
// link is dressed up.
func TestTheDialogHostIsWhatIsImported(t *testing.T) {
	for _, text := range []string{".https://evil.example/sub", "https://evil.example/sub x", "Моя подписка: https://evil.example/sub"} {
		if got := URLHost(SubscriptionURL(text)); got != "evil.example" {
			t.Errorf("%q: %q", text, got)
		}
	}
}

func TestPercentDecodeLeavesBrokenEscapes(t *testing.T) {
	for in, want := range map[string]string{
		"a%zz%4":   "a%zz%4",
		"a+b%20c":  "a+b c",
		"%D1т":     "�т",
		"no-codes": "no-codes",
	} {
		if got := percentDecode(in); got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
}
