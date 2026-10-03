package subscription

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"net/url"
	"slices"
	"unicode/utf8"

	"github.com/klausms17/vpn/libxray/client/importer"
	"github.com/klausms17/vpn/libxray/client/model"
)

var (
	// ErrAlreadyAdded refuses a link that is saved as a subscription already.
	ErrAlreadyAdded = errors.New("Эта подписка уже добавлена")
	// ErrTooMany refuses a subscription beyond model.MaxSubscriptions.
	ErrTooMany = fmt.Errorf("Больше %d подписок добавить нельзя: удалите ненужные", model.MaxSubscriptions)
	// ErrLongLink refuses a link no panel gives.
	ErrLongLink = errors.New("Слишком длинная ссылка на подписку")
	// ErrGone: the subscription was deleted.
	ErrGone = errors.New("Подписки больше нет")
)

// Updater adds and refreshes subscriptions. The download comes first; then
// the result is merged into the state as saved at that moment, so that a
// change made meanwhile is not undone and no server gets two ids.
type Updater struct {
	// Snapshot reads the state as saved.
	Snapshot func() (model.ProfilesState, error)
	// Save saves what change makes of the state as saved; change is quick.
	Save func(change func(model.ProfilesState) model.ProfilesState) error
	// Fetch downloads a subscription with the device headers.
	Fetch Fetch
	// Check refuses servers (the service's allowlist); nil lets all through.
	Check importer.Check
	// NewID names new subscriptions and servers.
	NewID func() string
	// Now is the time in milliseconds.
	Now func() int64
}

// Outcome is what an add or a refresh did.
type Outcome struct {
	// Subscription is the subscription as saved.
	Subscription model.Subscription
	// Servers is how many servers it has now.
	Servers int
	// Applied is false when the panel sent no servers and the old ones stay.
	Applied bool
	// Errors has a line for each entry that could not be used.
	Errors []string
	// Dropped servers did not fit under model.MaxProfiles.
	Dropped int
	// RunningChanged: the running server has other settings now, or is
	// gone; the tunnel should restart.
	RunningChanged bool
}

// Add downloads link and saves it as a new subscription, named after the
// panel's title or its host. It is refused when the link is saved already.
func (u *Updater) Add(ctx context.Context, link string) (Outcome, error) {
	if utf8.RuneCountInString(link) > maxPanelURL {
		return Outcome{}, ErrLongLink
	}
	saved, err := u.Snapshot()
	if err != nil {
		return Outcome{}, err
	}
	if err := room(saved, link); err != nil {
		return Outcome{}, err
	}
	f, err := Download(ctx, link, u.Fetch, u.Check, nil, nil)
	if err != nil {
		return Outcome{}, err
	}
	// Cancelled meanwhile: the servers that were still being pinned are
	// missing from f, so nothing is saved (Android's CancellationException).
	if err := ctx.Err(); err != nil {
		return Outcome{}, err
	}
	sub := model.Subscription{ID: u.NewID(), Name: model.ClipName(cmp.Or(f.Title, host(link), "Подписка")), URL: link}
	now := u.Now()
	var before, after model.ProfilesState
	var refused error
	if err := u.Save(func(s model.ProfilesState) model.ProfilesState {
		// Added meanwhile, from another window.
		if refused = room(s, link); refused != nil {
			return s
		}
		before = s
		s.Subscriptions = append(slices.Clone(s.Subscriptions), sub)
		after = Merge(s, sub.ID, f, now, u.NewID)
		return after
	}); err != nil {
		return Outcome{}, err
	}
	if refused != nil {
		return Outcome{}, refused
	}
	return outcome(before, after, sub.ID, f, "")
}

// Refresh downloads subscription id again and merges it. runningID is the
// server the tunnel runs ("" for none), for Outcome.RunningChanged. repin
// fetches pinned certificates again instead of reusing those of unchanged
// links (a refresh the user asked for). A failed download is saved on the
// subscription, and the servers stay.
func (u *Updater) Refresh(ctx context.Context, id, runningID string, repin bool) (Outcome, error) {
	saved, err := u.Snapshot()
	if err != nil {
		return Outcome{}, err
	}
	i := slices.IndexFunc(saved.Subscriptions, func(s model.Subscription) bool { return s.ID == id })
	if i < 0 {
		return Outcome{}, ErrGone
	}
	var servers []model.StoredProfile
	for _, p := range saved.Profiles {
		if p.SubscriptionID == id {
			servers = append(servers, p)
		}
	}
	// Certificates pinned before: reused for unchanged servers, and kept
	// when a server cannot be reached right now.
	known := PinnedOf(servers)
	reuse := importer.Saved(known.SameLink)
	if repin {
		reuse = nil
	}
	f, err := Download(ctx, saved.Subscriptions[i].URL, u.Fetch, u.Check, reuse, known.SameServer)
	if err != nil {
		if ctx.Err() == nil {
			now := u.Now()
			_ = u.Save(func(s model.ProfilesState) model.ProfilesState { return MarkFailed(s, id, err.Error(), now) })
		}
		return Outcome{}, err
	}
	if err := ctx.Err(); err != nil {
		return Outcome{}, err
	}
	now := u.Now()
	var before, after model.ProfilesState
	if err := u.Save(func(s model.ProfilesState) model.ProfilesState {
		before = s
		after = Merge(s, id, f, now, u.NewID)
		return after
	}); err != nil {
		return Outcome{}, err
	}
	return outcome(before, after, id, f, runningID)
}

func outcome(before, after model.ProfilesState, id string, f Fetched, runningID string) (Outcome, error) {
	i := slices.IndexFunc(after.Subscriptions, func(s model.Subscription) bool { return s.ID == id })
	if i < 0 {
		// Deleted while it was downloading.
		return Outcome{}, ErrGone
	}
	o := Outcome{
		Subscription:   after.Subscriptions[i],
		Applied:        len(f.Keys) > 0,
		Errors:         f.Errors,
		RunningChanged: RunningChanged(before, after, id, runningID),
	}
	for _, p := range after.Profiles {
		if p.SubscriptionID == id {
			o.Servers++
		}
	}
	if o.Applied {
		o.Dropped = len(f.Keys) - o.Servers + f.Dropped
	}
	return o, nil
}

// room refuses link when s has it already or has no room for it.
func room(s model.ProfilesState, link string) error {
	switch {
	case slices.ContainsFunc(s.Subscriptions, func(sub model.Subscription) bool { return sub.URL == link }):
		return ErrAlreadyAdded
	case len(s.Subscriptions) >= model.MaxSubscriptions:
		return ErrTooMany
	}
	return nil
}

// host is the host of link, or "".
func host(link string) string {
	u, err := url.Parse(link)
	if err != nil {
		return ""
	}
	return u.Hostname()
}
