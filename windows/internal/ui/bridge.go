package ui

import (
	"context"
	"errors"
	"time"

	"github.com/klausms17/vpn/windows/internal/ipc"
)

// Bridge is what the window's script calls. Each method is one request to
// the service; the answers come back as snapshot events.
type Bridge struct {
	link    *link
	version string
	// loaded is called once the page has loaded; the self-test exits then.
	loaded func()
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
