package model

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"
	"unicode/utf8"
)

func server(id, sub string) StoredProfile {
	return StoredProfile{ID: id, Name: id, Outbounds: json.RawMessage(`[]`), SubscriptionID: sub}
}

func key(link, tag string) Key {
	return Key{Name: tag, Link: link, Outbounds: json.RawMessage(fmt.Sprintf(`[%q]`, tag))}
}

func ids(ps []StoredProfile) []string {
	var out []string
	for _, p := range ps {
		out = append(out, p.ID)
	}
	return out
}

func counter() func() string {
	n := 0
	return func() string { n++; return fmt.Sprintf("id%d", n) }
}

func TestSelectingAServerThatIsGoneKeepsTheSelection(t *testing.T) {
	state := ProfilesState{Profiles: []StoredProfile{server("a", ""), server("b", "")}, SelectedID: "a"}
	if got := state.WithSelected("b").SelectedID; got != "b" {
		t.Errorf("selected %q", got)
	}
	// The list was replaced meanwhile: the clicked row's id no longer exists.
	if got := state.WithSelected("old"); !reflect.DeepEqual(got, state) {
		t.Errorf("changed: %+v", got)
	}
}

func TestAddingKeepsAWorkingSelectionAndRepairsABrokenOne(t *testing.T) {
	added := []StoredProfile{server("n1", ""), server("n2", "")}
	working := ProfilesState{Profiles: []StoredProfile{server("a", "")}, SelectedID: "a"}
	if got := working.WithAdded(added); got.SelectedID != "a" || len(got.Profiles) != 3 {
		t.Errorf("working selection: %+v", got)
	}
	dangling := ProfilesState{Profiles: []StoredProfile{server("a", "")}, SelectedID: "gone"}
	if got := dangling.WithAdded(added).SelectedID; got != "n1" {
		t.Errorf("dangling selection -> %q", got)
	}
	if got := (ProfilesState{}).WithAdded(added).SelectedID; got != "n1" {
		t.Errorf("empty state -> %q", got)
	}
	if got := working.WithAdded(nil); !reflect.DeepEqual(got, working) {
		t.Errorf("nothing added, but changed: %+v", got)
	}
}

func TestARepeatedKeyIsAddedOnce(t *testing.T) {
	ready := []Key{key("vless://a", "a"), key("vless://b", "b"), key("vless://a", "a"), key("vless://saved", "s")}
	var links []string
	for _, k := range FreshKeys(ready, map[string]bool{"vless://saved": true}) {
		links = append(links, k.Link)
	}
	if want := []string{"vless://a", "vless://b"}; !reflect.DeepEqual(links, want) {
		t.Errorf("fresh %v", links)
	}
}

func TestKeysWithoutALinkAreTheSameWhenTheirOutboundsAre(t *testing.T) {
	// A pasted JSON body has no share links.
	ready := []Key{key("", "x"), key("", "x"), key("", "y")}
	if got := len(FreshKeys(ready, nil)); got != 2 {
		t.Errorf("%d fresh keys", got)
	}
}

func TestTheSameKeysImportedTwiceAreSavedOnce(t *testing.T) {
	ready := []Key{key("vless://a", "a"), key("vless://b", "b")}
	newID := counter()
	once, added, left := ProfilesState{}.WithNewKeys(ready, newID, 7)
	if len(added) != 2 || left != 0 || added[0].Link != "vless://a" || added[1].Link != "vless://b" {
		t.Fatalf("added %+v, %d left", added, left)
	}
	if !reflect.DeepEqual(once.Profiles, added) || once.SelectedID != added[0].ID || added[0].CreatedAt != 7 {
		t.Errorf("state %+v", once)
	}
	twice, again, _ := once.WithNewKeys(ready, newID, 8)
	if len(again) != 0 || !reflect.DeepEqual(twice, once) {
		t.Errorf("second import added %+v", again)
	}
}

func TestNoMoreThanMaxProfilesAreSaved(t *testing.T) {
	var ready []Key
	for i := range MaxProfiles - 1 {
		ready = append(ready, key(fmt.Sprintf("vless://%d", i), "k"))
	}
	full, _, left := ProfilesState{}.WithNewKeys(ready, counter(), 0)
	if len(full.Profiles) != MaxProfiles-1 || left != 0 {
		t.Fatalf("%d saved, %d left", len(full.Profiles), left)
	}
	more := []Key{key("vless://x", "x"), key("vless://y", "y"), key("vless://0", "repeated")}
	next, added, left := full.WithNewKeys(more, counter(), 0)
	if len(added) != 1 || added[0].Link != "vless://x" || left != 1 || len(next.Profiles) != MaxProfiles {
		t.Errorf("added %d, %d left, %d saved", len(added), left, len(next.Profiles))
	}
	if _, added, left := next.WithNewKeys(more[1:2], counter(), 0); len(added) != 0 || left != 1 {
		t.Errorf("into a full list: added %d, %d left", len(added), left)
	}
}

func TestUnnamedKeysAreNamedAfterTheirAddress(t *testing.T) {
	if got := (Key{Address: "203.0.113.10"}).Stored("x", "", 0).Name; got != "203.0.113.10" {
		t.Errorf("name %q", got)
	}
}

func TestNamesAreBounded(t *testing.T) {
	long := strings.Repeat("Я", 5000)
	got := (Key{Name: long}).Stored("x", "", 0).Name
	if utf8.RuneCountInString(got) != MaxName || !strings.HasSuffix(got, "…") || !strings.HasPrefix(got, "ЯЯЯ") {
		t.Errorf("name of %d characters: %q…", utf8.RuneCountInString(got), got[:12])
	}
	if got := (Key{Address: strings.Repeat("a", 300) + ".example"}).Label(); utf8.RuneCountInString(got) != MaxName {
		t.Errorf("address label of %d characters", utf8.RuneCountInString(got))
	}
	exact := strings.Repeat("ж", MaxName)
	if ClipName(exact) != exact || ClipName("Германия") != "Германия" {
		t.Error("a short name was changed")
	}
	state := ProfilesState{Profiles: []StoredProfile{server("a", "")}}.Renamed("a", long)
	if utf8.RuneCountInString(state.Profiles[0].Name) != MaxName {
		t.Errorf("renamed to %d characters", utf8.RuneCountInString(state.Profiles[0].Name))
	}
}

func TestDeletingTheSelectedServerSelectsTheFirstRemainingOne(t *testing.T) {
	state := ProfilesState{Profiles: []StoredProfile{server("a", ""), server("b", ""), server("c", "")}, SelectedID: "b"}
	next := state.WithoutProfile("b")
	if !reflect.DeepEqual(ids(next.Profiles), []string{"a", "c"}) || next.SelectedID != "a" {
		t.Errorf("%+v", next)
	}
	if !reflect.DeepEqual(ids(state.Profiles), []string{"a", "b", "c"}) {
		t.Error("the original state was changed")
	}
	last := ProfilesState{Profiles: []StoredProfile{server("a", "")}, SelectedID: "a"}.WithoutProfile("a")
	if last.SelectedID != "" {
		t.Errorf("selection %q after deleting the last server", last.SelectedID)
	}
}

func TestDeletingAnotherServerKeepsTheSelection(t *testing.T) {
	state := ProfilesState{Profiles: []StoredProfile{server("a", ""), server("b", "")}, SelectedID: "b"}
	next := state.WithoutProfile("a")
	if !reflect.DeepEqual(ids(next.Profiles), []string{"b"}) || next.SelectedID != "b" {
		t.Errorf("%+v", next)
	}
}

func TestDeletingASubscriptionKeepsASelectionOutsideIt(t *testing.T) {
	sub := Subscription{ID: "s", Name: "s", URL: "https://example.com/s"}
	other := Subscription{ID: "t", Name: "t", URL: "https://example.com/t"}
	state := ProfilesState{
		Profiles:      []StoredProfile{server("own", ""), server("s1", "s"), server("t1", "t")},
		Subscriptions: []Subscription{sub, other},
		SelectedID:    "t1",
	}
	next := state.WithoutSubscription("s")
	if !reflect.DeepEqual(ids(next.Profiles), []string{"own", "t1"}) || len(next.Subscriptions) != 1 || next.Subscriptions[0].ID != "t" || next.SelectedID != "t1" {
		t.Errorf("%+v", next)
	}
	// The selected server was in it: the first remaining one takes over.
	inside := state
	inside.SelectedID = "s1"
	if got := inside.WithoutSubscription("s").SelectedID; got != "own" {
		t.Errorf("selection %q", got)
	}
	only := ProfilesState{Profiles: []StoredProfile{server("s1", "s")}, Subscriptions: []Subscription{sub}, SelectedID: "s1"}
	if got := only.WithoutSubscription("s").SelectedID; got != "" {
		t.Errorf("selection %q", got)
	}
}

func TestRenamingChangesOnlyThatServer(t *testing.T) {
	state := ProfilesState{Profiles: []StoredProfile{server("a", ""), server("b", "")}, SelectedID: "a"}
	next := state.Renamed("a", "A")
	if next.Profiles[0].Name != "A" || next.Profiles[1].Name != "b" || state.Profiles[0].Name != "a" {
		t.Errorf("%+v / %+v", next.Profiles, state.Profiles)
	}
}

// The JSON stays the one of Android's Models.kt: what is not set is left
// out or written as its default, never as null.
func TestJSONMatchesTheAndroidModel(t *testing.T) {
	state := ProfilesState{Profiles: []StoredProfile{{ID: "a", Name: "A", Outbounds: json.RawMessage(`[{"protocol":"vless"}]`)}}}
	got, _ := json.Marshal(state)
	if empty, _ := json.Marshal(ProfilesState{}); string(empty) != `{"profiles":[],"subscriptions":[]}` {
		t.Errorf("empty state %s", empty)
	}
	want := `{"profiles":[{"id":"a","name":"A","protocol":"","address":"","port":0,"network":"","security":"","outbounds":[{"protocol":"vless"}],"createdAt":0}],"subscriptions":[]}`
	if string(got) != want {
		t.Errorf("JSON\n%s\nwant\n%s", got, want)
	}
	var back ProfilesState
	if err := json.Unmarshal([]byte(`{"profiles":[{"id":"a","name":"A","outbounds":[],"unknown":1}],"selectedId":"a"}`), &back); err != nil || back.SelectedID != "a" {
		t.Errorf("Android file: %+v %v", back, err)
	}
}
