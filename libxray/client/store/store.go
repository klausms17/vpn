// Package store keeps one JSON document in a file, as Android's
// JsonFileStore does. A write goes to a temp file that is synced and
// renamed over the old one, so a crash or power loss mid-write never loses
// the user's keys and a reader always sees a whole file. Changes go
// through Update, which holds the file's lock, so no change is lost.
package store

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/klausms17/vpn/libxray/internal/fsx"
)

// ErrCorrupt means the file was read but holds no valid document.
var ErrCorrupt = errors.New("файл повреждён")

// keepCorrupt is how many undecodable files are kept. The first ones are
// the likeliest to hold the user's keys; later ones are deleted, so a bug
// that keeps writing them cannot fill the disk.
const keepCorrupt = 3

// Codec seals the document on disk (DPAPI on Windows). Open must fail on
// anything Seal did not make.
type Codec interface {
	Seal(plain []byte) ([]byte, error)
	Open(sealed []byte) ([]byte, error)
}

// Store is one JSON document of type T in the file at path.
type Store[T any] struct {
	path  string
	def   func() T
	codec Codec
	log   func(string)
}

// New returns the store of path. def gives the value when there is no
// file; codec may be nil; log gets messages that never quote the content.
func New[T any](path string, def func() T, codec Codec, log func(string)) *Store[T] {
	return &Store[T]{path: path, def: def, codec: codec, log: log}
}

var (
	// One writer at a time per file, whichever Store value it uses.
	locks sync.Map // path -> *sync.Mutex
	// Files whose failed Read was logged: callers may read them often.
	unreadableLogged sync.Map // path -> bool
)

// Read returns the saved value, or the default when there is none or it
// cannot be read. The file stays as it is.
func (s *Store[T]) Read() T {
	v, err := s.ReadStrict()
	if err != nil {
		if _, logged := unreadableLogged.LoadOrStore(s.path, true); !logged {
			s.log(fmt.Sprintf("%s cannot be read (%s), using the default", s.name(), errorKind(err)))
		}
		return s.def()
	}
	return v
}

// ReadStrict returns the saved value, or the default only when there is no
// file. An error wraps ErrCorrupt when the file was read but its content
// is unusable; anything else is an I/O error.
func (s *Store[T]) ReadStrict() (T, error) {
	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return s.def(), nil
	}
	if err != nil {
		var zero T
		return zero, err
	}
	return s.decode(data)
}

// Update reads the saved value, applies transform and saves the result if
// it changed, while no other change of this file can run. Keep transform
// quick: no network, no waiting.
//
// A file that was read but cannot be decoded is set aside and the change
// starts from the default: refusing would block every save for good. A
// file that cannot be read at all is left alone and the update refused.
func (s *Store[T]) Update(transform func(T) T) (T, error) {
	mu, _ := locks.LoadOrStore(s.path, new(sync.Mutex))
	mu.(*sync.Mutex).Lock()
	defer mu.(*sync.Mutex).Unlock()

	current, err := s.ReadStrict()
	if errors.Is(err, ErrCorrupt) {
		if err := s.setAside(err); err != nil {
			return current, err
		}
		current = s.def()
	} else if err != nil {
		return current, err
	}
	before, err := json.Marshal(current)
	if err != nil {
		return current, err
	}
	next := transform(current)
	after, err := json.Marshal(next)
	if err != nil {
		return current, err
	}
	if bytes.Equal(before, after) {
		return next, nil
	}
	return next, s.save(after)
}

func (s *Store[T]) decode(data []byte) (T, error) {
	var v T
	if s.codec != nil {
		plain, err := s.codec.Open(data)
		if err != nil {
			return v, s.corrupt(err)
		}
		data = plain
	}
	if err := json.Unmarshal(data, &v); err != nil {
		return v, s.corrupt(err)
	}
	return v, nil
}

func (s *Store[T]) corrupt(cause error) error {
	return &corruptError{name: s.name(), cause: cause}
}

// setAside renames the undecodable file to "<name>.corrupt-<ms>",
// untouched, so the keys in it are not lost, or deletes it once
// keepCorrupt copies exist.
func (s *Store[T]) setAside(cause error) error {
	dir := filepath.Dir(s.path)
	prefix := s.name() + ".corrupt-"
	kept := 0
	if entries, err := os.ReadDir(dir); err == nil {
		for _, e := range entries {
			if strings.HasPrefix(e.Name(), prefix) {
				kept++
			}
		}
	}
	var err error
	where := "deleted"
	if kept < keepCorrupt {
		copyName := fmt.Sprintf("%s%d", prefix, time.Now().UnixMilli())
		err = os.Rename(s.path, filepath.Join(dir, copyName))
		where = "moved to " + copyName
	} else {
		err = os.Remove(s.path)
	}
	if err != nil {
		// Still there: refuse rather than write over it.
		return cause
	}
	s.log(fmt.Sprintf("%s cannot be decoded (%s), %s; starting over", s.name(), errorKind(cause), where))
	return nil
}

func (s *Store[T]) save(data []byte) error {
	if s.codec != nil {
		sealed, err := s.codec.Seal(data)
		if err != nil {
			return fmt.Errorf("не удалось сохранить %s: %w", s.name(), err)
		}
		data = sealed
	}
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// A temp file of its own for every write, never shared with another.
	suffix := make([]byte, 8)
	_, _ = rand.Read(suffix)
	tmp := filepath.Join(dir, s.name()+"."+hex.EncodeToString(suffix)+".tmp")
	defer os.Remove(tmp)
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	_, werr := f.Write(data)
	serr := f.Sync()
	cerr := f.Close()
	if err := errors.Join(werr, serr, cerr); err != nil {
		return err
	}
	if err := fsx.Replace(tmp, s.path); err != nil {
		return fmt.Errorf("не удалось сохранить %s: %w", s.name(), err)
	}
	return nil
}

func (s *Store[T]) name() string { return filepath.Base(s.path) }

type corruptError struct {
	name  string
	cause error
}

func (e *corruptError) Error() string {
	return fmt.Sprintf("не удалось прочитать %s: %s", e.name, ErrCorrupt)
}

func (e *corruptError) Is(target error) bool { return target == ErrCorrupt }

// errorKind names an error without its text, which may quote the file and
// so the user's keys.
func errorKind(err error) string {
	var ce *corruptError
	if errors.As(err, &ce) {
		err = ce.cause
	}
	return fmt.Sprintf("%T", err)
}
