package service

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
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
}

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
		return h.logs(), nil
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
		if _, err := h.profiles.Update(func(s model.ProfilesState) model.ProfilesState {
			next, a, l := s.WithNewKeys(keys, newID, h.now().UnixMilli())
			added, left = a, l
			return next
		}); err != nil {
			return ipc.ImportResult{}, err
		}
	}
	if len(added) > 0 {
		h.log(fmt.Sprintf("added %d servers", len(added)))
		h.broadcast(ipc.NewEvent(ipc.EventProfiles, h.profilesData()))
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
	var changed bool
	next, err := h.profiles.Update(func(s model.ProfilesState) model.ProfilesState {
		n := s.WithSelected(id)
		changed = n.SelectedID != s.SelectedID
		return n
	})
	switch {
	case err != nil:
		return err
	case next.SelectedID != id:
		return errNoServer
	case changed:
		h.log("server selected by a window")
		h.broadcast(ipc.NewEvent(ipc.EventProfiles, h.profilesData()))
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
	if _, err := h.profiles.Update(func(s model.ProfilesState) model.ProfilesState {
		found = slices.ContainsFunc(s.Profiles, func(p model.StoredProfile) bool { return p.ID == id })
		return s.Renamed(id, name)
	}); err != nil {
		return err
	}
	if !found {
		return errNoServer
	}
	h.broadcast(ipc.NewEvent(ipc.EventProfiles, h.profilesData()))
	return nil
}

// delete removes server id. If it was the selected one, the tunnel moves
// to the next server, or stops when none is left, as on Android.
func (h *handler) delete(id string) error {
	var wasSelected bool
	next, err := h.profiles.Update(func(s model.ProfilesState) model.ProfilesState {
		wasSelected = s.SelectedID == id
		return s.WithoutProfile(id)
	})
	if err != nil {
		return err
	}
	h.log("server deleted by a window")
	h.broadcast(ipc.NewEvent(ipc.EventProfiles, h.profilesData()))
	h.pinger.keep(ids(next.Profiles))
	if wasSelected {
		if len(next.Profiles) == 0 {
			h.tunnel.Disconnect()
		} else {
			h.tunnel.Reconnect()
		}
	}
	return nil
}

// ping checks the servers with ids, or every server when ids is empty.
func (h *handler) ping(ids []string) error {
	saved, err := h.profiles.ReadStrict()
	if err != nil {
		return err
	}
	servers := saved.Profiles
	if len(ids) > 0 {
		servers = slices.DeleteFunc(slices.Clone(servers), func(p model.StoredProfile) bool { return !slices.Contains(ids, p.ID) })
	}
	h.pinger.ping(servers)
	return nil
}

func (h *handler) setSettings(s ipc.Settings) error {
	s, err := cleanSettings(s, h.site)
	if err != nil {
		return err
	}
	var changed bool
	if _, err := h.settings.Update(func(old ipc.Settings) ipc.Settings {
		changed = !equalSettings(old, s)
		return s
	}); err != nil {
		return err
	}
	if changed {
		h.log("settings changed by a window")
		h.broadcast(ipc.NewEvent(ipc.EventSettings, s))
		h.tunnel.Reconnect()
	}
	return nil
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
	ja, _ := json.Marshal(a)
	jb, _ := json.Marshal(b)
	return string(ja) == string(jb)
}

// newID is a random UUID, as Android names servers.
func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
