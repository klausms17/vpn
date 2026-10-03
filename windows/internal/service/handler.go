package service

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/klausms17/vpn/libxray/client/importer"
	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/client/store"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// Tunnel is the engine as the windows drive it.
type Tunnel interface {
	Connect()
	Disconnect()
	// Reconnect starts a running tunnel again with the saved server and
	// settings.
	Reconnect()
	Status() ipc.Status
}

// handler answers the windows' requests (ipc.Handler) and greets new ones.
type handler struct {
	tunnel   Tunnel
	profiles *store.Store[model.ProfilesState]
	settings *store.Store[ipc.Settings]
	pinger   *pinger
	// keys reads the keys in pasted text (importer.Keys, with the service's
	// check).
	keys func(ctx context.Context, text string) ([]model.Key, []string, error)
	// site checks a site the user typed (libxray's UserRuleEntry).
	site func(string) string
	// logs reads the logs for the window's journal.
	logs      func() ipc.Logs
	broadcast func(ipc.Event)
	log       func(string)
	now       func() time.Time
	// importing holds the one import that may run at a time: each can
	// fetch certificates over the network.
	importing sync.Mutex
	// A change of the servers or the settings is saved and announced under
	// its lock, so that the windows get the changes in the order they were
	// made.
	profilesMu sync.Mutex
	settingsMu sync.Mutex
	// journalMu reads the logs once at a time; a read serves the windows
	// that ask within logsFresh, as each masks two logs of 64 KB.
	journalMu sync.Mutex
	journalAt time.Time
	journal   ipc.Logs
}

const logsFresh = time.Second

var (
	errBadRequest = errors.New("неверный запрос: обновите Kirov VPN")
	errImporting  = errors.New("Ключи ещё добавляются, подождите")
	errNoServer   = errors.New("Этого сервера уже нет в списке")
)

func (h *handler) handle(ctx context.Context, op string, args json.RawMessage) (any, error) {
	switch op {
	case ipc.OpStatus:
		return h.tunnel.Status(), nil
	case ipc.OpConnect:
		h.log("connect asked by a window")
		h.tunnel.Connect()
		return nil, nil
	case ipc.OpDisconnect:
		h.log("disconnect asked by a window")
		h.tunnel.Disconnect()
		return nil, nil
	case ipc.OpImport:
		var a ipc.ImportArgs
		if err := json.Unmarshal(args, &a); err != nil {
			return nil, errBadRequest
		}
		return h.importText(ctx, a.Text)
	case ipc.OpSelect:
		var a ipc.IDArgs
		if err := json.Unmarshal(args, &a); err != nil {
			return nil, errBadRequest
		}
		return nil, h.selectServer(a.ID)
	case ipc.OpRename:
		var a ipc.RenameArgs
		if err := json.Unmarshal(args, &a); err != nil {
			return nil, errBadRequest
		}
		return nil, h.rename(a.ID, a.Name)
	case ipc.OpDelete:
		var a ipc.IDArgs
		if err := json.Unmarshal(args, &a); err != nil {
			return nil, errBadRequest
		}
		return nil, h.delete(a.ID)
	case ipc.OpPing:
		var a ipc.PingArgs
		if err := json.Unmarshal(args, &a); err != nil {
			return nil, errBadRequest
		}
		return nil, h.ping(a.IDs)
	case ipc.OpSetSettings:
		var s ipc.Settings
		if err := json.Unmarshal(args, &s); err != nil {
			return nil, errBadRequest
		}
		return nil, h.setSettings(s)
	case ipc.OpLogs:
		return h.readLogs(), nil
	}
	return nil, ipc.ErrUnknownOp
}

func (h *handler) importText(ctx context.Context, text string) (ipc.ImportResult, error) {
	text = strings.TrimSpace(text)
	switch {
	case text == "":
		return ipc.ImportResult{}, errors.New("Вставьте ключ сервера")
	case len(text) > ipc.MaxImport:
		return ipc.ImportResult{}, errors.New("Слишком длинный текст: вставьте только ключи")
	case importer.SubscriptionURL(text) != "":
		return ipc.ImportResult{}, errors.New("Подписки появятся в следующей версии. Пока вставьте ключ сервера (vless://, trojan://, ss://…)")
	case !h.importing.TryLock():
		return ipc.ImportResult{}, errImporting
	}
	defer h.importing.Unlock()
	keys, skipped, err := h.keys(ctx, text)
	if err != nil {
		return ipc.ImportResult{}, err
	}
	var (
		added []model.StoredProfile
		left  int
	)
	if len(keys) > 0 {
		// Inside the save, against the list as saved: nothing written
		// meanwhile is lost.
		if err := h.changeProfiles(func(s model.ProfilesState) model.ProfilesState {
			next, a, l := s.WithNewKeys(keys, newID, h.now().UnixMilli())
			added, left = a, l
			return next
		}); err != nil {
			return ipc.ImportResult{}, err
		}
	}
	if len(added) > 0 {
		h.log(fmt.Sprintf("added %d servers", len(added)))
	}
	message := importer.Summary(len(added), len(keys), skipped)
	if left > 0 {
		message = fmt.Sprintf("Добавлено серверов: %d. Больше %d серверов сохранить нельзя.", len(added), model.MaxProfiles)
	}
	return ipc.ImportResult{Added: len(added), Message: message}, nil
}

// selectServer makes id the server to connect to; a running tunnel moves
// to it.
func (h *handler) selectServer(id string) error {
	var changed, found bool
	err := h.changeProfiles(func(s model.ProfilesState) model.ProfilesState {
		n := s.WithSelected(id)
		changed, found = n.SelectedID != s.SelectedID, n.SelectedID == id
		return n
	})
	switch {
	case err != nil:
		return err
	case !found:
		return errNoServer
	case changed:
		h.log("server selected by a window")
		h.tunnel.Reconnect()
	}
	return nil
}

func (h *handler) rename(id, name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return errors.New("Введите имя сервера")
	}
	var found bool
	if err := h.changeProfiles(func(s model.ProfilesState) model.ProfilesState {
		found = slices.ContainsFunc(s.Profiles, func(p model.StoredProfile) bool { return p.ID == id })
		return s.Renamed(id, name)
	}); err != nil {
		return err
	}
	if !found {
		return errNoServer
	}
	return nil
}

// delete removes server id. If it was the selected one, the tunnel moves
// to the next server, or stops when none is left, as on Android.
func (h *handler) delete(id string) error {
	var wasSelected bool
	var left map[string]bool
	if err := h.changeProfiles(func(s model.ProfilesState) model.ProfilesState {
		wasSelected = s.SelectedID == id
		next := s.WithoutProfile(id)
		left = ids(next.Profiles)
		return next
	}); err != nil {
		return err
	}
	h.log("server deleted by a window")
	h.pinger.keep(left)
	if wasSelected {
		if len(left) == 0 {
			h.tunnel.Disconnect()
		} else {
			h.tunnel.Reconnect()
		}
	}
	return nil
}

// changeProfiles saves change of the servers and tells the windows, in the
// order the changes are made. The windows hear of it only if it changed
// something.
func (h *handler) changeProfiles(change func(model.ProfilesState) model.ProfilesState) error {
	h.profilesMu.Lock()
	defer h.profilesMu.Unlock()
	var changed bool
	if _, err := h.profiles.Update(func(s model.ProfilesState) model.ProfilesState {
		next := change(s)
		changed = !reflect.DeepEqual(next, s)
		return next
	}); err != nil {
		return err
	}
	if changed {
		h.broadcast(ipc.NewEvent(ipc.EventProfiles, h.profilesData()))
	}
	return nil
}

// ping checks the servers with ids, or every server when ids is empty.
func (h *handler) ping(ids []string) error {
	return h.pinger.ping(ids)
}

func (h *handler) setSettings(s ipc.Settings) error {
	s, err := cleanSettings(s, h.site)
	if err != nil {
		return err
	}
	h.settingsMu.Lock()
	var old ipc.Settings
	_, err = h.settings.Update(func(o ipc.Settings) ipc.Settings {
		old = o
		return s
	})
	changed := err == nil && !equalSettings(old, s)
	if changed {
		h.broadcast(ipc.NewEvent(ipc.EventSettings, s))
	}
	h.settingsMu.Unlock()
	if err != nil {
		return err
	}
	if changed {
		h.log("settings changed by a window")
	}
	// Whether to connect at boot does not change the running tunnel.
	if !sameRouting(old, s) {
		h.tunnel.Reconnect()
	}
	return nil
}

// readLogs returns the journal, read at most once per logsFresh.
func (h *handler) readLogs() ipc.Logs {
	h.journalMu.Lock()
	defer h.journalMu.Unlock()
	now := h.now()
	if h.journalAt.IsZero() || now.Sub(h.journalAt) >= logsFresh || now.Before(h.journalAt) {
		h.journal, h.journalAt = h.logs(), now
	}
	return h.journal
}

// greet is what a new window gets first: the status, the servers, their
// checks and the settings.
func (h *handler) greet() []ipc.Event {
	return []ipc.Event{
		ipc.NewEvent(ipc.EventStatus, h.tunnel.Status()),
		ipc.NewEvent(ipc.EventProfiles, h.profilesData()),
		ipc.NewEvent(ipc.EventPings, h.pinger.current()),
		ipc.NewEvent(ipc.EventSettings, h.settings.Read()),
	}
}

func (h *handler) profilesData() ipc.Profiles {
	s := h.profiles.Read()
	out := ipc.Profiles{Profiles: []ipc.Profile{}, SelectedID: s.SelectedID}
	for _, p := range s.Profiles {
		out.Profiles = append(out.Profiles, ipc.Profile{ID: p.ID, Name: p.Name, Protocol: p.Protocol, Security: p.Security})
	}
	return out
}

func ids(profiles []model.StoredProfile) map[string]bool {
	out := make(map[string]bool, len(profiles))
	for _, p := range profiles {
		out[p.ID] = true
	}
	return out
}

func equalSettings(a, b ipc.Settings) bool {
	return sameRouting(a, b) && a.AutoConnect == b.AutoConnect
}

// sameRouting tells whether a and b send traffic the same way. An empty
// list is the same as none.
func sameRouting(a, b ipc.Settings) bool {
	return a.Mode == b.Mode && a.TorrentsDirect == b.TorrentsDirect &&
		slices.Equal(a.DirectSites, b.DirectSites) && slices.Equal(a.ProxySites, b.ProxySites) &&
		slices.Equal(a.BlockSites, b.BlockSites) &&
		slices.Equal(a.DirectPrograms, b.DirectPrograms) && slices.Equal(a.ProxyPrograms, b.ProxyPrograms)
}

// newID is a random UUID, as Android names servers.
func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
