package subscription

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/model"
)

// memory is a store in memory, as the service's: Save runs the change on
// the state as saved, under one lock.
type memory struct {
	mu    sync.Mutex
	state model.ProfilesState
	saves int
	ids   func() string
}

func (m *memory) snapshot() (model.ProfilesState, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.state, nil
}

func (m *memory) save(change func(model.ProfilesState) model.ProfilesState) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.state = change(m.state)
	m.saves++
	return nil
}

func updater(m *memory, fetch Fetch) *Updater {
	clock := now
	if m.ids == nil {
		m.ids = ids()
	}
	return &Updater{
		Snapshot: m.snapshot, Save: m.save, Fetch: fetch, NewID: m.ids,
		Now: func() int64 { clock += 1000; return clock },
	}
}

const link = "https://sub.example.com/abc"

func TestAddSavesTheSubscriptionAndItsServers(t *testing.T) {
	m := &memory{}
	o, err := updater(m, answer(libxray.FetchResult{Body: body(keyDE, keyNL), ProfileTitle: "Kirov VPN", UserInfo: "total=10"})).Add(context.Background(), link)
	if err != nil {
		t.Fatal(err)
	}
	if o.Subscription.Name != "Kirov VPN" || o.Subscription.URL != link || o.Servers != 2 || !o.Applied || o.RunningChanged {
		t.Errorf("outcome %+v", o)
	}
	s := m.state
	if len(s.Subscriptions) != 1 || len(s.Profiles) != 2 || s.Profiles[0].SubscriptionID != s.Subscriptions[0].ID || s.SelectedID != s.Profiles[0].ID {
		t.Errorf("state %+v", s)
	}
	if s.Subscriptions[0].UserInfo != "total=10" || s.Subscriptions[0].UpdatedAt == 0 {
		t.Errorf("subscription %+v", s.Subscriptions[0])
	}
}

func TestAnUntitledSubscriptionIsNamedAfterItsHost(t *testing.T) {
	m := &memory{}
	o, err := updater(m, answer(libxray.FetchResult{Body: body(keyDE)})).Add(context.Background(), "https://user:pw@sub.example.com:8443/x")
	if err != nil || o.Subscription.Name != "sub.example.com" {
		t.Errorf("name %q, err %v", o.Subscription.Name, err)
	}
	long := strings.Repeat("Д", 300)
	o, err = updater(m, answer(libxray.FetchResult{Body: body(keyNL), ProfileTitle: long})).Add(context.Background(), link)
	if err != nil || len([]rune(o.Subscription.Name)) != model.MaxName {
		t.Errorf("name of %d characters, err %v", len([]rune(o.Subscription.Name)), err)
	}
}

func TestASubscriptionIsAddedOnce(t *testing.T) {
	m := &memory{}
	fetch := answer(libxray.FetchResult{Body: body(keyDE)})
	if _, err := updater(m, fetch).Add(context.Background(), link); err != nil {
		t.Fatal(err)
	}
	if _, err := updater(m, fetch).Add(context.Background(), link); !errors.Is(err, ErrAlreadyAdded) {
		t.Errorf("err %v", err)
	}
	// Added from another window while this one was downloading.
	m = &memory{}
	var u *Updater
	u = updater(m, func(ctx context.Context, url string) (*libxray.FetchResult, error) {
		_ = m.save(func(s model.ProfilesState) model.ProfilesState {
			s.Subscriptions = append(s.Subscriptions, model.Subscription{ID: "other", URL: link})
			return s
		})
		return &libxray.FetchResult{Body: body(keyDE)}, nil
	})
	if _, err := u.Add(context.Background(), link); !errors.Is(err, ErrAlreadyAdded) || len(m.state.Subscriptions) != 1 || len(m.state.Profiles) != 0 {
		t.Errorf("err %v, state %+v", err, m.state)
	}
}

func TestASubscriptionWithoutServersIsSavedWithItsNotice(t *testing.T) {
	m := &memory{}
	o, err := updater(m, answer(libxray.FetchResult{HwidMaxDevices: true})).Add(context.Background(), link)
	if err != nil || o.Applied || o.Servers != 0 || o.Subscription.Notice != HWIDLimit || len(m.state.Subscriptions) != 1 {
		t.Errorf("outcome %+v, err %v", o, err)
	}
}

func TestAFailedAddSavesNothing(t *testing.T) {
	m := &memory{}
	failing := func(context.Context, string) (*libxray.FetchResult, error) {
		return nil, errors.New("HTTP 404 Not Found")
	}
	if _, err := updater(m, failing).Add(context.Background(), link); err == nil || m.saves != 0 {
		t.Errorf("err %v, %d saves", err, m.saves)
	}
}

func added(t *testing.T, keys ...string) (*memory, string) {
	t.Helper()
	m := &memory{}
	o, err := updater(m, answer(libxray.FetchResult{Body: body(keys...)})).Add(context.Background(), link)
	if err != nil {
		t.Fatal(err)
	}
	return m, o.Subscription.ID
}

func TestRefreshReplacesTheServers(t *testing.T) {
	m, id := added(t, keyDE, keyNL)
	de := m.state.Profiles[0].ID
	fi := strings.ReplaceAll(strings.ReplaceAll(keyNL, "nl.example.com", "fi.example.com"), "Нидерланды", "Финляндия")
	o, err := updater(m, answer(libxray.FetchResult{Body: body(keyDE, fi)})).Refresh(context.Background(), id, de, false)
	if err != nil {
		t.Fatal(err)
	}
	if o.Servers != 2 || !o.Applied || o.RunningChanged {
		t.Errorf("outcome %+v", o)
	}
	if m.state.Profiles[0].ID != de || m.state.Profiles[1].Name != "Финляндия" || m.state.SelectedID != de {
		t.Errorf("state %+v", m.state)
	}
	// The running server left the subscription.
	o, err = updater(m, answer(libxray.FetchResult{Body: body(fi)})).Refresh(context.Background(), id, de, false)
	if err != nil || !o.RunningChanged || m.state.SelectedID == de {
		t.Errorf("outcome %+v, err %v", o, err)
	}
}

func TestAFailedRefreshKeepsTheServersAndSaysWhy(t *testing.T) {
	m, id := added(t, keyDE)
	before := fmt.Sprint(m.state.Profiles)
	failing := func(context.Context, string) (*libxray.FetchResult, error) {
		return nil, errors.New("HTTP 502 Bad Gateway")
	}
	if _, err := updater(m, failing).Refresh(context.Background(), id, "", true); err == nil || err.Error() != "HTTP 502 Bad Gateway" {
		t.Errorf("err %v", err)
	}
	if fmt.Sprint(m.state.Profiles) != before || m.state.Subscriptions[0].LastError != "HTTP 502 Bad Gateway" {
		t.Errorf("state %+v", m.state)
	}
	// A refresh that was called off says nothing.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	m.state.Subscriptions[0].LastError = ""
	if _, err := updater(m, failing).Refresh(ctx, id, "", false); err == nil || m.state.Subscriptions[0].LastError != "" {
		t.Errorf("err %v, last error %q", err, m.state.Subscriptions[0].LastError)
	}
}

func TestARefreshOfAGoneSubscription(t *testing.T) {
	m, id := added(t, keyDE)
	if _, err := updater(m, answer(libxray.FetchResult{Body: body(keyDE)})).Refresh(context.Background(), "nope", "", false); !errors.Is(err, ErrGone) {
		t.Errorf("err %v", err)
	}
	// Deleted while it was downloading: not brought back.
	u := updater(m, func(context.Context, string) (*libxray.FetchResult, error) {
		_ = m.save(func(s model.ProfilesState) model.ProfilesState { return s.WithoutSubscription(id) })
		return &libxray.FetchResult{Body: body(keyNL)}, nil
	})
	if _, err := u.Refresh(context.Background(), id, "", false); !errors.Is(err, ErrGone) || len(m.state.Subscriptions) != 0 || len(m.state.Profiles) != 0 {
		t.Errorf("err %v, state %+v", err, m.state)
	}
}

func TestAddIsBounded(t *testing.T) {
	downloads := 0
	fetch := func(context.Context, string) (*libxray.FetchResult, error) {
		downloads++
		return &libxray.FetchResult{Body: body(keyDE)}, nil
	}
	m := &memory{}
	for i := range model.MaxSubscriptions {
		m.state.Subscriptions = append(m.state.Subscriptions, model.Subscription{ID: fmt.Sprint(i), URL: fmt.Sprintf("%s/%d", link, i)})
	}
	if _, err := updater(m, fetch).Add(context.Background(), link); !errors.Is(err, ErrTooMany) {
		t.Errorf("one too many: err %v", err)
	}
	if _, err := updater(&memory{}, fetch).Add(context.Background(), link+"/"+strings.Repeat("a", 1000)); !errors.Is(err, ErrLongLink) {
		t.Errorf("a long link: err %v", err)
	}
	if downloads != 0 {
		t.Errorf("%d downloads for links refused", downloads)
	}
}
