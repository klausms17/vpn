package service

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/klausms17/vpn/libxray/client/account"
	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/client/store"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// accounts holds this PC's account (docs/accounts/PLAN.md) for every
// window: one per PC, like the servers. The session token stays here,
// sealed; the windows see the email and where the account stands. Once the
// owner grants access, the account's link becomes a subscription marked as
// the account's and refreshed like the others; signing out removes it.
type accounts struct {
	// client is nil in a build without an accounts service.
	client *account.Client
	state  *store.Store[account.State]
	subs   *subscriptions
	// saved reads the servers as saved; change saves a change of them;
	// drop removes a subscription with its servers.
	saved   func() model.ProfilesState
	change  func(func(model.ProfilesState) model.ProfilesState) error
	drop    func(id string) error
	publish func(ipc.Account)
	now     func() int64
	log     func(string)

	// work lets one request to the accounts service run at a time.
	work sync.Mutex
	mu   sync.Mutex
	busy bool
}

// newAccounts gives h the account of this PC, kept in state and asked of
// the accounts service through client (nil: none in this build). Its
// subscription is added, saved and removed as h's other changes.
func newAccounts(h *handler, client *account.Client, state *store.Store[account.State], now func() int64) *accounts {
	return &accounts{
		client:  client,
		state:   state,
		subs:    h.subs,
		saved:   h.profiles.Read,
		change:  h.changeProfiles,
		drop:    h.deleteSubscription,
		publish: func(a ipc.Account) { h.broadcast(ipc.NewEvent(ipc.EventAccount, a)) },
		now:     now,
		log:     h.log,
	}
}

// Bounds of what a window sends: an address of RFC 5321, and more than
// the service takes as a password, so that it says what is wrong.
const (
	maxEmail    = 254
	maxPassword = 1024
)

var (
	errNoAccounts    = errors.New("В этой версии Kirov VPN нет аккаунтов")
	errAccountBusy   = errors.New("Подождите: предыдущий запрос ещё выполняется")
	errSignedIn      = errors.New("Вы уже вошли в аккаунт. Чтобы войти в другой, сначала выйдите")
	errNotSignedIn   = errors.New("Вы не вошли в аккаунт")
	errEnterEmail    = errors.New("Введите почту")
	errLongInput     = errors.New("Слишком длинная почта или пароль")
	errEnterPassword = errors.New("Введите пароль")
)

// shown is the account as the windows show it.
func (a *accounts) shown() ipc.Account {
	a.mu.Lock()
	busy := a.busy
	a.mu.Unlock()
	if a.client == nil {
		return ipc.Account{}
	}
	s := a.state.Read()
	return ipc.Account{Available: true, Email: s.Email, Status: string(s.Status), Busy: busy}
}

func (a *accounts) announce() { a.publish(a.shown()) }

// begin takes the one request at a time, or says one runs already.
func (a *accounts) begin() (func(), error) {
	if a.client == nil {
		return nil, errNoAccounts
	}
	if !a.work.TryLock() {
		return nil, errAccountBusy
	}
	a.setBusy(true)
	return func() {
		a.setBusy(false)
		a.work.Unlock()
	}, nil
}

func (a *accounts) setBusy(on bool) {
	a.mu.Lock()
	a.busy = on
	a.mu.Unlock()
	a.announce()
}

// typed checks what a window sent: the address, and the password when
// needed.
func typed(args ipc.AccountArgs, password bool) (string, error) {
	email := strings.TrimSpace(args.Email)
	switch {
	case email == "":
		return "", errEnterEmail
	case len(email) > maxEmail || len(args.Password) > maxPassword || !utf8.ValidString(args.Password):
		return "", errLongInput
	case password && args.Password == "":
		return "", errEnterPassword
	}
	return strings.ToLower(email), nil
}

func (a *accounts) register(ctx context.Context, args ipc.AccountArgs) (ipc.AccountResult, error) {
	email, err := typed(args, true)
	if err != nil {
		return ipc.AccountResult{}, err
	}
	done, err := a.begin()
	if err != nil {
		return ipc.AccountResult{}, err
	}
	defer done()
	if a.state.Read().SignedIn() {
		return ipc.AccountResult{}, errSignedIn
	}
	if err := a.client.Register(ctx, email, args.Password); err != nil {
		return ipc.AccountResult{}, err
	}
	if err := a.save(account.State{Email: email, Status: account.Unconfirmed}); err != nil {
		return ipc.AccountResult{}, err
	}
	a.log("account signed up, waiting for the email to be confirmed")
	return ipc.AccountResult{Message: fmt.Sprintf("Мы отправили вам письмо для подтверждения на %s. "+
		"Пожалуйста, завершите регистрацию, пройдя по ссылке из письма.", email)}, nil
}

func (a *accounts) resend(ctx context.Context, args ipc.AccountArgs) (ipc.AccountResult, error) {
	if args.Email == "" {
		args.Email = a.state.Read().Email
	}
	email, err := typed(args, false)
	if err != nil {
		return ipc.AccountResult{}, err
	}
	done, err := a.begin()
	if err != nil {
		return ipc.AccountResult{}, err
	}
	defer done()
	if err := a.client.Resend(ctx, email); err != nil {
		return ipc.AccountResult{}, err
	}
	return ipc.AccountResult{Message: fmt.Sprintf("Письмо отправлено ещё раз на %s. Если его нет, загляните в папку «Спам».", email)}, nil
}

func (a *accounts) forgot(ctx context.Context, args ipc.AccountArgs) (ipc.AccountResult, error) {
	email, err := typed(args, false)
	if err != nil {
		return ipc.AccountResult{}, err
	}
	done, err := a.begin()
	if err != nil {
		return ipc.AccountResult{}, err
	}
	defer done()
	if err := a.client.Forgot(ctx, email); err != nil {
		return ipc.AccountResult{}, err
	}
	return ipc.AccountResult{Message: fmt.Sprintf("Мы отправили письмо со ссылкой для нового пароля на %s. "+
		"Если его нет, загляните в папку «Спам».", email)}, nil
}

func (a *accounts) login(ctx context.Context, args ipc.AccountArgs) (ipc.AccountResult, error) {
	email, err := typed(args, true)
	if err != nil {
		return ipc.AccountResult{}, err
	}
	done, err := a.begin()
	if err != nil {
		return ipc.AccountResult{}, err
	}
	defer done()
	if a.state.Read().SignedIn() {
		return ipc.AccountResult{}, errSignedIn
	}
	token, acc, err := a.client.Login(ctx, email, args.Password)
	if account.IsUnconfirmed(err) {
		if serr := a.save(account.State{Email: email, Status: account.Unconfirmed}); serr != nil {
			return ipc.AccountResult{}, serr
		}
		return ipc.AccountResult{}, err
	}
	if err != nil {
		return ipc.AccountResult{}, err
	}
	s := account.State{Token: token}.With(acc, a.now())
	if err := a.save(s); err != nil {
		return ipc.AccountResult{}, err
	}
	a.log("signed in to the account, " + string(acc.Status))
	switch acc.Status {
	case account.Active:
		if err := a.attachSaved(ctx, s, acc.SubscriptionURL); err != nil {
			return ipc.AccountResult{Message: "Вы вошли, но серверы аккаунта пока не загрузились: " + err.Error() +
				". Kirov VPN попробует ещё раз сам."}, nil
		}
		return ipc.AccountResult{Message: "Вы вошли. Серверы аккаунта добавлены."}, nil
	}
	// The account view says what waiting or a refusal means.
	return ipc.AccountResult{Message: "Вы вошли."}, nil
}

// logout signs this PC out and removes the account's servers. The
// accounts service hears of it if it can; the token is forgotten anyway.
func (a *accounts) logout(ctx context.Context) error {
	done, err := a.begin()
	if err != nil {
		return err
	}
	defer done()
	s := a.state.Read()
	if s.SignedIn() {
		if err := a.client.Logout(ctx, s.Token); err != nil {
			a.log("the accounts service did not hear of the sign-out")
		}
	}
	return a.forget("signed out of the account")
}

func (a *accounts) remove(ctx context.Context, args ipc.AccountArgs) (ipc.AccountResult, error) {
	if args.Password == "" {
		return ipc.AccountResult{}, errEnterPassword
	}
	if len(args.Password) > maxPassword {
		return ipc.AccountResult{}, errLongInput
	}
	done, err := a.begin()
	if err != nil {
		return ipc.AccountResult{}, err
	}
	defer done()
	s := a.state.Read()
	if !s.SignedIn() {
		return ipc.AccountResult{}, errNotSignedIn
	}
	if err := a.client.Delete(ctx, s.Token, args.Password); err != nil {
		return ipc.AccountResult{}, err
	}
	if err := a.forget("account deleted"); err != nil {
		return ipc.AccountResult{}, err
	}
	return ipc.AccountResult{Message: "Аккаунт удалён, его серверы убраны с этого компьютера."}, nil
}

// check asks the accounts service about the account now (a window's
// «Проверить»).
func (a *accounts) check(ctx context.Context) error {
	done, err := a.begin()
	if err != nil {
		return err
	}
	defer done()
	if !a.state.Read().SignedIn() {
		return errNotSignedIn
	}
	return a.refresh(ctx)
}

// checkIfDue asks the accounts service when it is time (account.State.Due),
// quietly: often while the owner has not decided, else hourly.
func (a *accounts) checkIfDue(ctx context.Context) {
	if a.client == nil || !a.state.Read().Due(a.now()) || !a.work.TryLock() {
		return
	}
	defer a.work.Unlock()
	if err := a.refresh(ctx); err != nil {
		a.log("account not checked: " + err.Error())
	}
}

// refresh asks the accounts service about the account and follows it: the
// subscription comes once access is granted, and a session that is gone
// signs this PC out.
func (a *accounts) refresh(ctx context.Context) error {
	s := a.state.Read()
	acc, err := a.client.Me(ctx, s.Token)
	if account.IsSignedOut(err) {
		return a.forget("signed out by the accounts service")
	}
	if err != nil {
		return err
	}
	s = s.With(acc, a.now())
	if err := a.save(s); err != nil {
		return err
	}
	if acc.Status == account.Active {
		return a.attachSaved(ctx, s, acc.SubscriptionURL)
	}
	return nil
}

// attachSaved attaches link and remembers the subscription in the state,
// so that a missing one is tried again soon (account.State.Due).
func (a *accounts) attachSaved(ctx context.Context, s account.State, link string) error {
	id, err := a.attach(ctx, link)
	if err != nil {
		return err
	}
	if id != s.SubscriptionID {
		s.SubscriptionID = id
		return a.save(s)
	}
	return nil
}

// attach makes link the account's subscription, and returns its id: the
// one it is already, the same link added by hand, or a new one.
func (a *accounts) attach(ctx context.Context, link string) (string, error) {
	subs := a.saved().Subscriptions
	if i := slices.IndexFunc(subs, func(s model.Subscription) bool { return s.Account }); i >= 0 {
		if subs[i].URL == link {
			return subs[i].ID, nil
		}
		// The owner gave the account a new link: the servers follow it.
		if err := a.change(func(st model.ProfilesState) model.ProfilesState {
			return marked(st, func(s model.Subscription) bool { return s.Account }, link)
		}); err != nil {
			return "", err
		}
		a.log("the account's subscription got a new link")
		_, err := a.subs.refreshOne(ctx, subs[i].ID, false)
		return subs[i].ID, err
	}
	if !slices.ContainsFunc(subs, func(s model.Subscription) bool { return s.URL == link }) {
		if _, err := a.subs.add(ctx, link); err != nil {
			return "", err
		}
	}
	var id string
	err := a.change(func(st model.ProfilesState) model.ProfilesState {
		st = marked(st, func(s model.Subscription) bool { return s.URL == link }, link)
		if i := slices.IndexFunc(st.Subscriptions, func(s model.Subscription) bool { return s.URL == link }); i >= 0 {
			id = st.Subscriptions[i].ID
		}
		return st
	})
	if err == nil && id == "" {
		// Deleted from a window meanwhile.
		err = errNoSubscription
	}
	return id, err
}

// marked marks the subscriptions which picks as the account's, with link.
func marked(st model.ProfilesState, which func(model.Subscription) bool, link string) model.ProfilesState {
	st.Subscriptions = slices.Clone(st.Subscriptions)
	for i := range st.Subscriptions {
		if which(st.Subscriptions[i]) {
			st.Subscriptions[i].Account, st.Subscriptions[i].URL = true, link
		}
	}
	return st
}

// forget signs this PC out: the account's subscription and the state go.
func (a *accounts) forget(why string) error {
	for _, s := range a.saved().Subscriptions {
		if s.Account {
			if err := a.drop(s.ID); err != nil && !errors.Is(err, errNoSubscription) {
				return err
			}
		}
	}
	if err := a.save(account.State{}); err != nil {
		return err
	}
	a.log(why)
	return nil
}

func (a *accounts) save(s account.State) error {
	if _, err := a.state.Update(func(account.State) account.State { return s }); err != nil {
		return err
	}
	a.announce()
	return nil
}
