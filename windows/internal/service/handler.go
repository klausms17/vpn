package service

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
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
	Status() ipc.Status
}

// handler answers the windows' requests (ipc.Handler) and greets new ones.
type handler struct {
	tunnel   Tunnel
	profiles *store.Store[model.ProfilesState]
	// keys reads the keys in pasted text (importer.Keys, with the service's
	// check).
	keys      func(ctx context.Context, text string) ([]model.Key, []string, error)
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

// greet is what a new window gets first: the status and the servers.
func (h *handler) greet() []ipc.Event {
	return []ipc.Event{
		ipc.NewEvent(ipc.EventStatus, h.tunnel.Status()),
		ipc.NewEvent(ipc.EventProfiles, h.profilesData()),
	}
}

func (h *handler) profilesData() ipc.Profiles {
	s := h.profiles.Read()
	out := ipc.Profiles{Profiles: []ipc.Profile{}, SelectedID: s.SelectedID}
	for _, p := range s.Profiles {
		out.Profiles = append(out.Profiles, ipc.Profile{ID: p.ID, Name: p.Name, Protocol: p.Protocol})
	}
	return out
}

// newID is a random UUID, as Android names servers.
func newID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
