package subscription

import (
	"encoding/json"
	"regexp"
	"strings"
	"testing"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/model"
)

// The cases of the Android app's DeviceHeadersTest and PinnedTest.

func TestTheHWIDRecipeNeverChanges(t *testing.T) {
	// A different value here makes every device a new one in the panel.
	if got := HashID("0123456789abcdef"); got != "1012707cdd34d59dbc64b03534a44dc9" {
		t.Errorf("got %s", got)
	}
}

func TestTheHWIDFitsThePanelsRule(t *testing.T) {
	// Remnawave ignores ids outside this rule.
	rule := regexp.MustCompile(`^[a-zA-Z0-9=-]{10,64}$`)
	for _, id := range []string{"0123456789abcdef", "ffffffffffffffff", "1", "{D3B1C7A0-0F4E-4A51-9B8B-6B1E0C1D2E3F}"} {
		if h := HashID(id); len(h) != 32 || !rule.MatchString(h) || strings.ToLower(h) != h {
			t.Errorf("%q: %q", id, h)
		}
	}
}

func TestTheModelIsPrintableASCIIWithoutARepeatedBrand(t *testing.T) {
	for _, c := range [][3]string{
		{"Google", "Pixel 8", "Google Pixel 8"},
		{"Xiaomi", "Xiaomi 13T Pro", "Xiaomi 13T Pro"},
		{"samsung", "SM-S911B", "samsung SM-S911B"},
		{"OnePlus", "", "OnePlus"},
		{"LENOVO", "20XW0026RT", "LENOVO 20XW0026RT"},
		{"", "Virtual Machine", "Virtual Machine"},
		// Control characters and non-ASCII are dropped, spaces folded.
		{"HONOR", "X8\x00  Лайт Lite\n", "HONOR X8 Lite"},
	} {
		if got := Model(c[0], c[1]); got != c[2] {
			t.Errorf("%q %q: %q, want %q", c[0], c[1], got, c[2])
		}
	}
	if got := Model("Brand", strings.Repeat("M", 100)); len(got) != 64 {
		t.Errorf("%d characters", len(got))
	}
}

func TestPrintableTrimsAndCuts(t *testing.T) {
	for _, c := range []struct {
		in   string
		max  int
		want string
	}{
		{" 14 ", 32, "14"},
		{"abc def", 4, "abc"},
		{"\t\a", 32, ""},
		{"10.0.26100", 32, "10.0.26100"},
	} {
		if got := Printable(c.in, c.max); got != c.want {
			t.Errorf("%q: %q, want %q", c.in, got, c.want)
		}
	}
}

func TestTheHeadersAreTheAndroidApps(t *testing.T) {
	var got map[string]string
	d := Device{HWID: "h", OS: "Windows", OSVersion: "10.0.26100", Model: "LENOVO 20XW"}
	if err := json.Unmarshal([]byte(d.Headers()), &got); err != nil {
		t.Fatal(err)
	}
	want := map[string]string{"X-Hwid": "h", "X-Device-Os": "Windows", "X-Ver-Os": "10.0.26100", "X-Device-Model": "LENOVO 20XW"}
	if len(got) != len(want) {
		t.Fatalf("%v", got)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s: %q", k, got[k])
		}
	}
}

var savedPin = model.StoredProfile{
	ID: "h1", Name: "🇫🇮 5 дней", Protocol: "hysteria2", Address: "H.example.com", Port: 443,
	Link:      "hysteria2://pw@h.example.com:443?insecure=1#%F0%9F%87%AB%F0%9F%87%AE%205",
	Outbounds: json.RawMessage(`["pinned"]`), SubscriptionID: "s1",
}

func pinCandidate(link string) libxray.Profile {
	return libxray.Profile{Name: "x", Protocol: "hysteria2", Address: "h.example.com", Port: 443, Link: link, NeedsCertPin: true}
}

func TestARenamedServerReusesItsPin(t *testing.T) {
	// The panel changed only the name ("4 days left"): the same settings.
	p := pinCandidate("hysteria2://pw@h.example.com:443?insecure=1#%F0%9F%87%AB%F0%9F%87%AE%204")
	pinned := PinnedOf([]model.StoredProfile{savedPin})
	if string(pinned.SameLink(p)) != `["pinned"]` || string(pinned.SameServer(p)) != `["pinned"]` {
		t.Error("the pin was not reused")
	}
}

func TestChangedSettingsAreOnlyAFallback(t *testing.T) {
	// Another password: not reused, but kept if the server cannot be
	// reached now.
	p := pinCandidate("hysteria2://other@h.example.com:443?insecure=1#x")
	pinned := PinnedOf([]model.StoredProfile{savedPin})
	if pinned.SameLink(p) != nil || string(pinned.SameServer(p)) != `["pinned"]` {
		t.Error("wrong pins")
	}
}

func TestOtherServersAndNoneGetNothing(t *testing.T) {
	other := pinCandidate("hysteria2://pw@other.example.com:443?insecure=1#x")
	other.Address = "other.example.com"
	if PinnedOf([]model.StoredProfile{savedPin}).SameServer(other) != nil || PinnedOf(nil).SameServer(pinCandidate(savedPin.Link)) != nil {
		t.Error("a pin for another server")
	}
}
