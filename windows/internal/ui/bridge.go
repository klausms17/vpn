package ui

import (
	"context"
	"errors"
	"path/filepath"
	"time"

	"github.com/klausms17/vpn/libxray/client/applog"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// Bridge is what the window's script calls. Each method is one request to
// the service; the answers come back as snapshot events.
type Bridge struct {
	link    *link
	version string
	// uiLog is the window's own log, the journal's last section.
	uiLog string
	// loaded is called once the page has loaded; the self-test exits then.
	loaded func()
	// copyText puts text on the clipboard; pickProgram asks the user for a
	// program and returns its path, "" if they changed their mind. Both are
	// set once the app exists.
	copyText    func(string) bool
	pickProgram func() (string, error)
}

// Snapshot returns what the window shows now, for a page that just loaded.
func (b *Bridge) Snapshot() Snapshot { return b.link.snapshot() }

// Version is the app's version, for the window's footer.
func (b *Bridge) Version() string { return b.version }

// Connect asks the service to connect.
func (b *Bridge) Connect() error { return b.request(ipc.OpConnect, nil, nil) }

// Disconnect asks the service to disconnect.
func (b *Bridge) Disconnect() error { return b.request(ipc.OpDisconnect, nil, nil) }

// Import adds the keys in text and returns what it did, for the user.
func (b *Bridge) Import(text string) (string, error) {
	if len(text) > ipc.MaxImport {
		return "", errors.New("Слишком длинный текст: вставьте только ключи")
	}
	var r ipc.ImportResult
	// Certificates of servers that need one are fetched meanwhile.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	if err := b.link.call(ctx, ipc.OpImport, ipc.ImportArgs{Text: text}, &r); err != nil {
		return "", err
	}
	return r.Message, nil
}

// Select makes server id the one to connect to.
func (b *Bridge) Select(id string) error { return b.request(ipc.OpSelect, ipc.IDArgs{ID: id}, nil) }

// Rename gives server id a new name.
func (b *Bridge) Rename(id, name string) error {
	return b.request(ipc.OpRename, ipc.RenameArgs{ID: id, Name: name}, nil)
}

// Delete removes server id.
func (b *Bridge) Delete(id string) error { return b.request(ipc.OpDelete, ipc.IDArgs{ID: id}, nil) }

// Ping checks the servers with ids, or all of them when there are none.
func (b *Bridge) Ping(ids []string) error { return b.request(ipc.OpPing, ipc.PingArgs{IDs: ids}, nil) }

// SaveSettings saves the settings; the service checks them first.
func (b *Bridge) SaveSettings(s ipc.Settings) error { return b.request(ipc.OpSetSettings, s, nil) }

// Logs returns the journal: the service's and the core's logs, then the
// window's own, which is there even when the service is not.
func (b *Bridge) Logs() ipc.Logs {
	var l ipc.Logs
	if err := b.request(ipc.OpLogs, nil, &l); err != nil {
		l = ipc.Logs{Sections: []ipc.LogSection{{Title: "Служба Kirov VPN", Text: err.Error()}}}
	}
	l.Sections = append(l.Sections, ipc.LogSection{Title: "Окно Kirov VPN", Text: applog.Tail(b.uiLog, logTail, false)})
	return l
}

// logTail is how much of the window's log the journal shows.
const logTail = 64 << 10

// Copy puts text on the clipboard.
func (b *Bridge) Copy(text string) bool { return b.copyText(text) }

// PickProgram asks the user for a program and returns its file name, ""
// if they changed their mind.
func (b *Bridge) PickProgram() (string, error) {
	path, err := b.pickProgram()
	if err != nil || path == "" {
		return "", err
	}
	return filepath.Base(path), nil
}

// Loaded tells that the page has loaded.
func (b *Bridge) Loaded() {
	if b.loaded != nil {
		b.loaded()
	}
}

func (b *Bridge) request(op string, args, result any) error {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return b.link.call(ctx, op, args, result)
}
