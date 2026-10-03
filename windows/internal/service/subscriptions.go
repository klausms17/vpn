package service

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"slices"
	"sync"

	"github.com/klausms17/vpn/libxray/client/importer"
	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/client/subscription"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// subscriptions adds and refreshes the subscriptions, for the windows and
// by itself. Downloads run one at a time, the others wait for it or until
// their window goes away; meanwhile their subscriptions show as updating.
type subscriptions struct {
	updater *subscription.Updater
	// saved reads the servers as saved; running names the server the
	// tunnel runs ("" for none).
	saved   func() model.ProfilesState
	running func() string
	// changed tells the windows that a subscription's look changed.
	changed func()
	// after follows a download that changed the servers: the checks of
	// servers that are gone are forgotten, and the tunnel restarts when
	// restart is set.
	after func(left map[string]bool, restart bool)
	now   func() int64
	log   func(string)

	// busy holds a token while a download runs.
	busy chan struct{}

	mu sync.Mutex
	// updating counts the refreshes of each subscription asked for and not
	// finished.
	updating map[string]int
	kicked   bool
}

// newSubscriptions gives h its subscriptions, downloaded with fetch. They
// are saved and announced as h's other changes; the checks of servers that
// are gone are forgotten, and a tunnel whose server changed restarts.
func newSubscriptions(h *handler, fetch subscription.Fetch, now func() int64) *subscriptions {
	return &subscriptions{
		updater: &subscription.Updater{
			Snapshot: h.profiles.ReadStrict,
			Save:     h.changeProfiles,
			Fetch:    fetch,
			Check:    importer.ForService,
			NewID:    newID,
			Now:      now,
		},
		saved:   h.profiles.Read,
		running: func() string { return runningID(h.tunnel.Status()) },
		changed: h.publishProfiles,
		after: func(left map[string]bool, restart bool) {
			h.pinger.keep(left)
			if restart {
				h.tunnel.Reconnect()
			}
		},
		now:      now,
		log:      h.log,
		busy:     make(chan struct{}, 1),
		updating: map[string]int{},
	}
}

var (
	errNoSubscription  = errors.New("Этой подписки уже нет")
	errNoSubscriptions = errors.New("Подписок пока нет")
)

// add downloads link and saves it as a new subscription.
func (s *subscriptions) add(ctx context.Context, link string) (ipc.ImportResult, error) {
	if err := s.lock(ctx); err != nil {
		return ipc.ImportResult{}, err
	}
	defer s.unlock()
	o, err := s.updater.Add(ctx, link)
	if err != nil {
		return ipc.ImportResult{}, err
	}
	s.log(fmt.Sprintf("subscription added: %d servers", o.Servers))
	s.after(ids(s.saved().Profiles), false)
	message := fmt.Sprintf("Подписка «%s»: серверов %d", o.Subscription.Name, o.Servers)
	if !o.Applied {
		message = fmt.Sprintf("Подписка «%s» добавлена без серверов: %s", o.Subscription.Name,
			cmp.Or(o.Subscription.Notice, "сервер подписки их не прислал"))
	}
	return ipc.ImportResult{Added: o.Servers, Message: message + tooMany(o) + skipped(o)}, nil
}

// refresh downloads subscription id again, or each one when id is empty,
// fetching pinned certificates again, and says how it went.
func (s *subscriptions) refresh(ctx context.Context, id string) (string, error) {
	subs := s.saved().Subscriptions
	if id == "" && len(subs) == 1 {
		id = subs[0].ID
	}
	if id != "" {
		o, err := s.refreshOne(ctx, id, true)
		if err != nil {
			return "", failure(err)
		}
		return refreshed(o), nil
	}
	if len(subs) == 0 {
		return "", errNoSubscriptions
	}
	var failed []string
	for _, sub := range subs {
		if _, err := s.refreshOne(ctx, sub.ID, true); err != nil && !errors.Is(err, errNoSubscription) {
			if ctx.Err() != nil {
				return "", failure(err)
			}
			failed = append(failed, fmt.Sprintf("«%s»: %v", sub.Name, err))
		}
	}
	if len(failed) == 0 {
		return fmt.Sprintf("Подписки обновлены: %d", len(subs)), nil
	}
	return fmt.Sprintf("Обновлено подписок: %d из %d. Не удалось обновить %s", len(subs)-len(failed), len(subs), failed[0]), nil
}

// refreshStale refreshes, one after another and quietly, the
// subscriptions without a fresh list for an hour. Android does it when the
// app is opened; the service also does it at start and every hour.
func (s *subscriptions) refreshStale(ctx context.Context) {
	now := s.now()
	for _, sub := range s.saved().Subscriptions {
		if ctx.Err() != nil {
			return
		}
		if !subscription.IsStale(sub, now) {
			continue
		}
		if _, err := s.refreshOne(ctx, sub.ID, false); err != nil && !errors.Is(err, errNoSubscription) {
			s.log("subscription not refreshed: " + err.Error())
		}
	}
}

// kick runs refreshStale in the background, unless it runs already.
func (s *subscriptions) kick(ctx context.Context) {
	s.mu.Lock()
	if s.kicked {
		s.mu.Unlock()
		return
	}
	s.kicked = true
	s.mu.Unlock()
	go func() {
		defer func() {
			s.mu.Lock()
			s.kicked = false
			s.mu.Unlock()
		}()
		s.refreshStale(ctx)
	}()
}

// refreshOne downloads subscription id again; repin is a refresh the user
// asked for. A failed download is saved on the subscription.
func (s *subscriptions) refreshOne(ctx context.Context, id string, repin bool) (subscription.Outcome, error) {
	if !slices.ContainsFunc(s.saved().Subscriptions, func(sub model.Subscription) bool { return sub.ID == id }) {
		return subscription.Outcome{}, errNoSubscription
	}
	s.setUpdating(id, true)
	defer s.setUpdating(id, false)
	if err := s.lock(ctx); err != nil {
		return subscription.Outcome{}, err
	}
	defer s.unlock()
	o, err := s.updater.Refresh(ctx, id, s.running(), repin)
	if errors.Is(err, subscription.ErrGone) {
		return o, errNoSubscription
	}
	if err != nil {
		return o, err
	}
	s.log(fmt.Sprintf("subscription refreshed: %d servers, applied %t", o.Servers, o.Applied))
	s.after(ids(s.saved().Profiles), o.RunningChanged)
	return o, nil
}

// lock waits until no other download runs, or until ctx ends.
func (s *subscriptions) lock(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case s.busy <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (s *subscriptions) unlock() { <-s.busy }

func (s *subscriptions) setUpdating(id string, on bool) {
	s.mu.Lock()
	if on {
		s.updating[id]++
	} else if s.updating[id]--; s.updating[id] <= 0 {
		delete(s.updating, id)
	}
	s.mu.Unlock()
	s.changed()
}

// shown is sub as the windows show it.
func (s *subscriptions) shown(sub model.Subscription) ipc.Subscription {
	s.mu.Lock()
	updating := s.updating[sub.ID] > 0
	s.mu.Unlock()
	u := subscription.ParseUsage(sub.UserInfo)
	return ipc.Subscription{
		ID: sub.ID, Name: sub.Name, UpdatedAt: sub.UpdatedAt, LastAttemptAt: sub.LastAttemptAt,
		LastError: sub.LastError, Notice: sub.Notice, Announce: sub.Announce,
		Used: u.Used, Total: u.Total, Expire: u.Expire, Updating: updating, Account: sub.Account,
	}
}

// refreshed is the message after a refresh the user asked for.
func refreshed(o subscription.Outcome) string {
	if !o.Applied {
		return cmp.Or(o.Subscription.Notice, "Сервер подписки не прислал серверов, оставлены прежние")
	}
	return fmt.Sprintf("Подписка обновлена: серверов %d", o.Servers) + tooMany(o) + skipped(o)
}

func failure(err error) error {
	if errors.Is(err, errNoSubscription) {
		return err
	}
	return fmt.Errorf("Не удалось обновить подписку: %w", err)
}

// skipped says how many of the panel's servers cannot be used here, and
// why the first one.
func skipped(o subscription.Outcome) string {
	if len(o.Errors) == 0 {
		return ""
	}
	return fmt.Sprintf(". Пропущено: %d (%s)", len(o.Errors), o.Errors[0])
}

// tooMany says that servers of o did not fit.
func tooMany(o subscription.Outcome) string {
	if o.Dropped <= 0 {
		return ""
	}
	return fmt.Sprintf(". Больше %d серверов сохранить нельзя.", model.MaxProfiles)
}

// runningID names the server the tunnel runs or starts, else "".
func runningID(st ipc.Status) string {
	if st.State == ipc.Connected || st.State == ipc.Connecting {
		return st.ProfileID
	}
	return ""
}
