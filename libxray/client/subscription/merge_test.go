package subscription

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"github.com/klausms17/vpn/libxray/client/model"
)

// The cases of the Android app's SubscriptionUpdaterTest.

const (
	now  = int64(1_800_000_000_000)
	hour = int64(60 * 60_000)
)

type server struct {
	name, address string
	port          int
	sni, path, id string
}

func (s server) outbounds() json.RawMessage {
	stream := map[string]any{"network": "raw", "security": "tls", "tlsSettings": map[string]any{"serverName": s.sni}}
	if s.path != "" {
		stream["network"] = "ws"
		stream["wsSettings"] = map[string]any{"path": s.path}
	}
	out, _ := json.Marshal([]any{map[string]any{
		"protocol":       "vless",
		"settings":       map[string]any{"address": s.address, "id": s.id},
		"streamSettings": stream,
	}})
	return out
}

func (s server) key() model.Key {
	if s.port == 0 {
		s.port = 443
	}
	if s.id == "" {
		s.id = "u1"
	}
	network := "raw"
	if s.path != "" {
		network = "ws"
	}
	return model.Key{
		Name: s.name, Protocol: "vless", Address: s.address, Port: s.port, Network: network, Security: "tls",
		Link:      fmt.Sprintf("vless://%s@%s:%d?sni=%s&path=%s#%s", s.id, s.address, s.port, s.sni, s.path, s.name),
		Outbounds: s.outbounds(),
	}
}

func parsed(name, address string) model.Key { return server{name: name, address: address}.key() }

func stored(id string, k model.Key, sub string) model.StoredProfile { return k.Stored(id, sub, 1) }

var (
	sub = model.Subscription{ID: "s1", Name: "Друзья", URL: "https://sub.example.com/abc", UpdatedAt: now - 2*hour}
	own = stored("own", parsed("Мой", "own.example.com"), "")
)

func ids() func() string {
	n := 0
	return func() string {
		n++
		return fmt.Sprintf("new%d", n)
	}
}

func fetched(keys ...model.Key) Fetched {
	return Fetched{Keys: keys, UserInfo: "upload=0; download=1; total=2; expire=0"}
}

func profileIDs(s model.ProfilesState) []string {
	var out []string
	for _, p := range s.Profiles {
		out = append(out, p.ID)
	}
	return out
}

func renamed(k model.Key, name string) model.Key {
	link, _, _ := strings.Cut(k.Link, "#")
	k.Name, k.Link = name, link+"#"+name
	return k
}

func TestRefreshKeepsIdsAndSelection(t *testing.T) {
	nl, de := parsed("NL", "nl.example.com"), parsed("DE", "de.example.com")
	state := model.ProfilesState{Profiles: []model.StoredProfile{own, stored("a", nl, "s1"), stored("b", de, "s1")}, Subscriptions: []model.Subscription{sub}, SelectedID: "b"}
	// Remarks change between refreshes (days left), and with them the
	// links: the servers are still recognised.
	next := Merge(state, "s1", fetched(renamed(nl, "NL 29 дней"), renamed(de, "DE 29 дней")), now, ids())
	if got := profileIDs(next); !reflect.DeepEqual(got, []string{"own", "a", "b"}) {
		t.Fatalf("ids %v", got)
	}
	if sel, _ := next.Selected(); next.SelectedID != "b" || sel.Name != "DE 29 дней" {
		t.Errorf("selected %q named %q", next.SelectedID, sel.Name)
	}
	s := next.Subscriptions[0]
	if s.UpdatedAt != now || s.LastAttemptAt != now || s.UserInfo != "upload=0; download=1; total=2; expire=0" {
		t.Errorf("subscription %+v", s)
	}
}

func TestNewServersGetNewIdsAndGoneOnesLeave(t *testing.T) {
	nl := parsed("NL", "nl.example.com")
	state := model.ProfilesState{Profiles: []model.StoredProfile{own, stored("a", nl, "s1"), stored("b", parsed("DE", "de.example.com"), "s1")}, Subscriptions: []model.Subscription{sub}, SelectedID: "own"}
	next := Merge(state, "s1", fetched(nl, parsed("FI", "fi.example.com")), now, ids())
	if got := profileIDs(next); !reflect.DeepEqual(got, []string{"own", "a", "new1"}) || next.SelectedID != "own" {
		t.Errorf("ids %v, selected %q", got, next.SelectedID)
	}
}

func TestSelectionMovesWhenTheSelectedServerIsGone(t *testing.T) {
	state := model.ProfilesState{Profiles: []model.StoredProfile{own, stored("a", parsed("NL", "nl.example.com"), "s1")}, Subscriptions: []model.Subscription{sub}, SelectedID: "a"}
	if next := Merge(state, "s1", fetched(parsed("FI", "fi.example.com")), now, ids()); next.SelectedID != "new1" {
		t.Errorf("selected %q", next.SelectedID)
	}
}

func TestFirstServerIsSelectedWhenNothingWas(t *testing.T) {
	state := model.ProfilesState{Subscriptions: []model.Subscription{sub}}
	if next := Merge(state, "s1", fetched(parsed("NL", "nl.example.com")), now, ids()); next.SelectedID != "new1" {
		t.Errorf("selected %q", next.SelectedID)
	}
}

func TestNoticesOnlyKeepTheServers(t *testing.T) {
	a := stored("a", parsed("NL", "nl.example.com"), "s1")
	old := sub
	old.UserInfo = "old"
	state := model.ProfilesState{Profiles: []model.StoredProfile{own, a}, Subscriptions: []model.Subscription{old}, SelectedID: "a"}
	next := Merge(state, "s1", Fetched{Notice: "Subscription expired"}, now, ids())
	if !reflect.DeepEqual(next.Profiles, state.Profiles) || next.SelectedID != "a" {
		t.Errorf("servers changed: %v", profileIDs(next))
	}
	s := next.Subscriptions[0]
	// Only a real list counts as fresh; the attempt is still recorded.
	if s.Notice != "Subscription expired" || s.UpdatedAt != sub.UpdatedAt || s.LastAttemptAt != now || s.UserInfo != "old" {
		t.Errorf("subscription %+v", s)
	}
}

func TestDeviceLimitKeepsTheServersAndShowsTheAnnounce(t *testing.T) {
	failed := sub
	failed.LastError = "HTTP 502 Bad Gateway"
	state := model.ProfilesState{Profiles: []model.StoredProfile{stored("a", parsed("NL", "nl.example.com"), "s1")}, Subscriptions: []model.Subscription{failed}, SelectedID: "a"}
	next := Merge(state, "s1", Fetched{Notice: HWIDLimit, Announce: "Напишите мне"}, now, ids())
	s := next.Subscriptions[0]
	if !reflect.DeepEqual(next.Profiles, state.Profiles) || s.Notice != HWIDLimit || s.Announce != "Напишите мне" || s.LastError != "" {
		t.Errorf("subscription %+v", s)
	}
}

func TestPanelAddressesAreKeptUntilAListSaysOtherwise(t *testing.T) {
	const report, app = "https://sub.example.com/klaus/report", "https://sub.example.com/app/version.json"
	state := model.ProfilesState{Profiles: []model.StoredProfile{stored("a", parsed("NL", "nl.example.com"), "s1")}, Subscriptions: []model.Subscription{sub}, SelectedID: "a"}
	with := fetched(parsed("NL", "nl.example.com"))
	with.ReportURL, with.AppURL = report, app
	first := Merge(state, "s1", with, now, ids())
	if s := first.Subscriptions[0]; s.ReportURL != report || s.AppURL != app {
		t.Fatalf("subscription %+v", s)
	}
	// Refused or only messages, without the headers: the addresses stay.
	refused := Merge(first, "s1", Fetched{Notice: HWIDLimit}, now, ids())
	if s := refused.Subscriptions[0]; s.ReportURL != report || s.AppURL != app {
		t.Errorf("refused: %+v", s)
	}
	// A real list without them: the owner took them away.
	removed := Merge(refused, "s1", fetched(parsed("NL", "nl.example.com")), now, ids())
	if s := removed.Subscriptions[0]; s.ReportURL != "" || s.AppURL != "" {
		t.Errorf("removed: %+v", s)
	}
}

func TestARealListClearsTheNotice(t *testing.T) {
	noted := sub
	noted.Notice = HWIDLimit
	state := model.ProfilesState{Subscriptions: []model.Subscription{noted}}
	if next := Merge(state, "s1", fetched(parsed("NL", "nl.example.com")), now, ids()); next.Subscriptions[0].Notice != "" {
		t.Errorf("notice %q", next.Subscriptions[0].Notice)
	}
}

func TestDeletedWhileDownloadingIsNotBroughtBack(t *testing.T) {
	state := model.ProfilesState{Profiles: []model.StoredProfile{own}, SelectedID: "own"}
	if next := Merge(state, "s1", fetched(parsed("NL", "nl.example.com")), now, ids()); !reflect.DeepEqual(next, state) {
		t.Errorf("changed: %+v", next)
	}
}

func TestOtherSubscriptionsAreUntouched(t *testing.T) {
	other := model.Subscription{ID: "s2", Name: "Другая", URL: "https://other.example.com/x"}
	b := stored("b", parsed("US", "us.example.com"), "s2")
	state := model.ProfilesState{Profiles: []model.StoredProfile{own, stored("a", parsed("NL", "nl.example.com"), "s1"), b}, Subscriptions: []model.Subscription{sub, other}, SelectedID: "b"}
	next := Merge(state, "s1", fetched(parsed("FI", "fi.example.com")), now, ids())
	if got := profileIDs(next); !reflect.DeepEqual(got, []string{"own", "b", "new1"}) {
		t.Errorf("ids %v", got)
	}
	if next.Subscriptions[1] != other || next.SelectedID != "b" {
		t.Errorf("other %+v, selected %q", next.Subscriptions[1], next.SelectedID)
	}
}

func TestTheServersFitUnderTheLimit(t *testing.T) {
	var many []model.StoredProfile
	for i := range model.MaxProfiles - 1 {
		many = append(many, stored(fmt.Sprintf("o%d", i), parsed("own", fmt.Sprintf("h%d.example.com", i)), ""))
	}
	state := model.ProfilesState{Profiles: many, Subscriptions: []model.Subscription{sub}, SelectedID: "o0"}
	next := Merge(state, "s1", fetched(parsed("NL", "nl.example.com"), parsed("DE", "de.example.com")), now, ids())
	if len(next.Profiles) != model.MaxProfiles || next.Profiles[model.MaxProfiles-1].Name != "NL" {
		t.Errorf("%d servers, last %q", len(next.Profiles), next.Profiles[len(next.Profiles)-1].Name)
	}
}

func TestSameAddressAndPortDifferentSNIKeepTheirOwnIds(t *testing.T) {
	// Two hosts on one node and port, told apart only by SNI and path.
	one := server{name: "A", address: "node.example.com", sni: "one.example.com", path: "/one"}.key()
	two := server{name: "B", address: "node.example.com", sni: "two.example.com", path: "/two"}.key()
	old := []model.StoredProfile{stored("x", one, "s1"), stored("y", two, "s1")}
	// Both renamed and listed the other way round.
	two2, one2 := two, one
	two2.Name, two2.Link = "B2", ""
	one2.Name, one2.Link = "A2", ""
	if got := matchedIDs(matchExisting(old, []model.Key{two2, one2})); !reflect.DeepEqual(got, []string{"y", "x"}) {
		t.Errorf("matches %v", got)
	}
}

func TestLinkMatchWinsOverWeakerKeys(t *testing.T) {
	a := server{name: "A", address: "node.example.com", sni: "a.example.com"}.key()
	b := server{name: "B", address: "node.example.com", sni: "b.example.com"}.key()
	old := []model.StoredProfile{stored("x", a, "s1"), stored("y", b, "s1")}
	// The first fresh server would take "x" by address and port alone, but
	// "x" belongs to the second one by its link.
	fresh := []model.Key{server{name: "C", address: "node.example.com", sni: "c.example.com"}.key(), a}
	if got := matchedIDs(matchExisting(old, fresh)); !reflect.DeepEqual(got, []string{"y", "x"}) {
		t.Errorf("matches %v", got)
	}
}

func TestChangedCredentialStillMatchesByEndpoint(t *testing.T) {
	before := server{name: "NL", address: "nl.example.com", sni: "nl.example.com", id: "old-uuid"}.key()
	after := server{name: "NL", address: "nl.example.com", sni: "nl.example.com", id: "new-uuid"}.key()
	if got := matchedIDs(matchExisting([]model.StoredProfile{stored("x", before, "s1")}, []model.Key{after})); !reflect.DeepEqual(got, []string{"x"}) {
		t.Errorf("matches %v", got)
	}
}

func TestAddressPortAndProtocolIsTheLastResort(t *testing.T) {
	before := server{name: "NL", address: "NL.example.com", sni: "old.example.com"}.key()
	after := server{name: "NL", address: "nl.example.com", sni: "random-1234.example.com"}.key()
	other := server{name: "NL", address: "nl.example.com", port: 8443}.key()
	if got := matchedIDs(matchExisting([]model.StoredProfile{stored("x", before, "s1")}, []model.Key{other, after})); !reflect.DeepEqual(got, []string{"", "x"}) {
		t.Errorf("matches %v", got)
	}
}

func matchedIDs(matches []*model.StoredProfile) []string {
	out := make([]string, len(matches))
	for i, m := range matches {
		if m != nil {
			out[i] = m.ID
		}
	}
	return out
}

func TestRunningChangedOnlyWhenItsOutboundsChangeOrItIsGone(t *testing.T) {
	nl := server{name: "NL", address: "nl.example.com", sni: "a.example.com"}.key()
	before := model.ProfilesState{Profiles: []model.StoredProfile{own, stored("a", nl, "s1")}, Subscriptions: []model.Subscription{sub}, SelectedID: "a"}

	// The same settings written another way are the same.
	reordered := nl
	var v any
	_ = json.Unmarshal(nl.Outbounds, &v)
	reordered.Outbounds, _ = json.MarshalIndent(v, "", "  ")
	if same := Merge(before, "s1", fetched(reordered), now, ids()); RunningChanged(before, same, "s1", "a") {
		t.Error("unchanged settings counted as a change")
	}

	changed := nl
	changed.Outbounds = server{address: "nl.example.com", sni: "b.example.com", id: "u1"}.outbounds()
	after := Merge(before, "s1", fetched(changed), now, ids())
	if after.SelectedID != "a" || !RunningChanged(before, after, "s1", "a") {
		t.Errorf("changed settings: selected %q", after.SelectedID)
	}

	gone := Merge(before, "s1", fetched(parsed("FI", "fi.example.com")), now, ids())
	if !RunningChanged(before, gone, "s1", "a") {
		t.Error("a gone server is no change")
	}
	// An own key is not this subscription's business.
	if RunningChanged(before, gone, "s1", "own") || RunningChanged(before, gone, "s1", "") {
		t.Error("an own key or no server counted")
	}
}

func TestFailureKeepsServersAndRecordsTheError(t *testing.T) {
	state := model.ProfilesState{Profiles: []model.StoredProfile{stored("a", parsed("NL", "nl.example.com"), "s1")}, Subscriptions: []model.Subscription{sub}, SelectedID: "a"}
	next := MarkFailed(state, "s1", "HTTP 502 Bad Gateway", now)
	s := next.Subscriptions[0]
	if !reflect.DeepEqual(next.Profiles, state.Profiles) || s.LastError != "HTTP 502 Bad Gateway" || s.LastAttemptAt != now || s.UpdatedAt != sub.UpdatedAt {
		t.Errorf("subscription %+v", s)
	}
	if state.Subscriptions[0].LastError != "" {
		t.Error("the state it was given was changed")
	}
}

func TestStaleAfterAnHour(t *testing.T) {
	at := func(updated, attempt int64) model.Subscription {
		s := sub
		s.UpdatedAt, s.LastAttemptAt = updated, attempt
		return s
	}
	for _, c := range []struct {
		sub  model.Subscription
		want bool
	}{
		{at(now-59*60_000, 0), false},
		{at(now-hour, 0), true},
		{at(0, 0), true},
		// The clock was set back.
		{at(now+hour, 0), true},
		// Not retried right after an attempt.
		{at(now-5*hour, now-60_000), false},
		{at(now-5*hour, now-RetryMs), true},
	} {
		if got := IsStale(c.sub, now); got != c.want {
			t.Errorf("updated %d, attempt %d: %v", now-c.sub.UpdatedAt, now-c.sub.LastAttemptAt, got)
		}
	}
}

func TestAStoredServerTakesTheParsedOne(t *testing.T) {
	k := server{name: "NL", address: "nl.example.com", sni: "x"}.key()
	s := k.Stored("a", "s1", 5)
	if string(s.Outbounds) != string(k.Outbounds) || s.Link != k.Link || s.SubscriptionID != "s1" || s.CreatedAt != 5 {
		t.Errorf("%+v", s)
	}
	// A server without a name is called by its address.
	k.Name = ""
	if got := k.Stored("b", "", 1).Name; got != "nl.example.com" {
		t.Errorf("name %q", got)
	}
}

func TestAnAccountsSubscriptionStaysTheAccounts(t *testing.T) {
	mine := sub
	mine.Account = true
	state := model.ProfilesState{Subscriptions: []model.Subscription{mine}}
	next := Merge(state, "s1", fetched(parsed("NL", "nl.example.com")), now, ids())
	next = MarkFailed(next, "s1", "HTTP 502", now)
	next = Merge(next, "s1", Fetched{Notice: HWIDLimit}, now, ids())
	if !next.Subscriptions[0].Account {
		t.Error("the account's mark was lost")
	}
}
