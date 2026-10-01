package store

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

type counter struct {
	Value int      `json:"value"`
	Log   []string `json:"log,omitempty"`
}

type env struct {
	dir    string
	path   string
	mu     sync.Mutex
	logged []string
}

func newEnv(t *testing.T) *env {
	dir := t.TempDir()
	return &env{dir: dir, path: filepath.Join(dir, "counter.json")}
}

// A new store per call: the lock must not depend on the instance.
func (e *env) store() *Store[counter] {
	return e.storeWith(nil)
}

func (e *env) storeWith(codec Codec) *Store[counter] {
	return New(e.path, func() counter { return counter{} }, codec, func(m string) {
		e.mu.Lock()
		defer e.mu.Unlock()
		e.logged = append(e.logged, m)
	})
}

func (e *env) files(t *testing.T) []string {
	entries, err := os.ReadDir(e.dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, x := range entries {
		names = append(names, x.Name())
	}
	return names
}

func (e *env) corruptCopies(t *testing.T) []string {
	return slices.DeleteFunc(e.files(t), func(n string) bool { return !strings.HasPrefix(n, "counter.json.corrupt-") })
}

func set(v int) func(counter) counter { return func(c counter) counter { c.Value = v; return c } }

func TestUpdatesFromTwoGoroutinesAreNeverLost(t *testing.T) {
	e := newEnv(t)
	const rounds = 200
	var wg sync.WaitGroup
	start := make(chan struct{})
	for _, name := range []string{"a", "b"} {
		wg.Go(func() {
			<-start
			for i := range rounds {
				if _, err := e.store().Update(func(c counter) counter {
					c.Value++
					c.Log = append(c.Log, fmt.Sprintf("%s%d", name, i))
					if len(c.Log) > 5 {
						c.Log = c.Log[len(c.Log)-5:]
					}
					return c
				}); err != nil {
					t.Error(err)
				}
			}
		})
	}
	close(start)
	wg.Wait()
	if got := e.store().Read().Value; got != 2*rounds {
		t.Errorf("value %d, want %d", got, 2*rounds)
	}
	// Every writer used a temp file of its own, and none is left behind.
	if got := e.files(t); !slices.Equal(got, []string{"counter.json"}) {
		t.Errorf("files %v", got)
	}
}

func TestUnchangedValueIsNotWritten(t *testing.T) {
	e := newEnv(t)
	if _, err := e.store().Update(set(1)); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Hour).Truncate(time.Second)
	if err := os.Chtimes(e.path, old, old); err != nil {
		t.Fatal(err)
	}
	got, err := e.store().Update(func(c counter) counter { return c })
	if err != nil || got.Value != 1 {
		t.Fatalf("%v %v", got, err)
	}
	if st, _ := os.Stat(e.path); !st.ModTime().Equal(old) {
		t.Errorf("rewritten at %v", st.ModTime())
	}
}

func TestBrokenFileIsSetAsideAndSavingWorksAgain(t *testing.T) {
	e := newEnv(t)
	broken := `{"value": "vless://secret-key@1.2.3.4"`
	os.WriteFile(e.path, []byte(broken), 0o600)
	// The lenient read gives the window something to show; the strict one refuses.
	if got := e.store().Read(); got.Value != 0 {
		t.Errorf("read %v", got)
	}
	_, err := e.store().ReadStrict()
	if !errors.Is(err, ErrCorrupt) || !strings.Contains(err.Error(), "counter.json") {
		t.Fatalf("strict read: %v", err)
	}
	// Not stuck: the change starts from the default, the broken file is kept untouched.
	if got, err := e.store().Update(set(5)); err != nil || got.Value != 5 {
		t.Fatalf("%v %v", got, err)
	}
	if got, err := e.store().ReadStrict(); err != nil || got.Value != 5 {
		t.Errorf("after update: %v %v", got, err)
	}
	copies := e.corruptCopies(t)
	if len(copies) != 1 {
		t.Fatalf("copies %v", copies)
	}
	if data, _ := os.ReadFile(filepath.Join(e.dir, copies[0])); string(data) != broken {
		t.Errorf("copy %q", data)
	}
	// Logged, but never the content: it holds the user's keys.
	if !strings.Contains(e.logged[len(e.logged)-1], "cannot be decoded") {
		t.Errorf("log %v", e.logged)
	}
	for _, m := range e.logged {
		if strings.Contains(m, "secret") || strings.Contains(m, "1.2.3.4") {
			t.Errorf("content in the log: %q", m)
		}
	}
}

func TestBrokenFileIsLoggedOnceAndLeftAloneByReads(t *testing.T) {
	e := newEnv(t)
	broken := `{"value": "vless://secret-key@1.2.3.4"`
	os.WriteFile(e.path, []byte(broken), 0o600)
	for range 3 {
		e.store().Read()
	}
	if len(e.logged) != 1 || !strings.HasPrefix(e.logged[0], "counter.json cannot be read") || strings.Contains(e.logged[0], "secret") {
		t.Errorf("log %v", e.logged)
	}
	// Nothing is copied or moved until a change sets it aside.
	if got := e.files(t); !slices.Equal(got, []string{"counter.json"}) {
		t.Errorf("files %v", got)
	}
	if data, _ := os.ReadFile(e.path); string(data) != broken {
		t.Error("the file was changed")
	}
}

func TestOnlyTheFirstBrokenFilesAreKept(t *testing.T) {
	e := newEnv(t)
	for i := range 5 {
		os.WriteFile(e.path, fmt.Appendf(nil, "broken %d", i), 0o600)
		if _, err := e.store().Update(set(i)); err != nil {
			t.Fatal(err)
		}
		time.Sleep(2 * time.Millisecond) // the copies are named by the millisecond
	}
	copies := e.corruptCopies(t)
	var contents []string
	for _, c := range copies {
		data, _ := os.ReadFile(filepath.Join(e.dir, c))
		contents = append(contents, string(data))
	}
	slices.Sort(contents)
	// The first ones are the likeliest to hold the user's own keys.
	if !slices.Equal(contents, []string{"broken 0", "broken 1", "broken 2"}) {
		t.Errorf("kept %v", contents)
	}
	if got := e.store().Read().Value; got != 4 {
		t.Errorf("value %d", got)
	}
}

// picky fails to decode like a model with a check of its own.
type picky struct{ Value int }

func (*picky) UnmarshalJSON([]byte) error { return errors.New("unexpected value") }

func TestAnyDecodingFailureIsSetAside(t *testing.T) {
	e := newEnv(t)
	os.WriteFile(e.path, []byte(`{"value": 1}`), 0o600)
	s := New(e.path, func() picky { return picky{} }, nil, func(string) {})
	got, err := s.Update(func(p picky) picky { p.Value = 5; return p })
	if err != nil || got.Value != 5 {
		t.Fatalf("%v %v", got, err)
	}
	if copies := e.corruptCopies(t); len(copies) != 1 {
		t.Errorf("copies %v", copies)
	}
}

func TestUnreadableFileIsLeftAlone(t *testing.T) {
	// Not a decoding problem but an I/O error: refuse and change nothing.
	e := newEnv(t)
	os.Mkdir(e.path, 0o700)
	_, err := e.store().Update(set(5))
	if err == nil || errors.Is(err, ErrCorrupt) {
		t.Fatalf("err %v", err)
	}
	if st, _ := os.Stat(e.path); !st.IsDir() {
		t.Error("the directory was replaced")
	}
	if copies := e.corruptCopies(t); len(copies) != 0 {
		t.Errorf("copies %v", copies)
	}
}

func TestMissingFileStartsFromTheDefault(t *testing.T) {
	e := newEnv(t)
	if got, err := e.store().Update(func(c counter) counter { c.Value++; return c }); err != nil || got.Value != 1 {
		t.Fatalf("%v %v", got, err)
	}
	if got, err := e.store().ReadStrict(); err != nil || got.Value != 1 {
		t.Errorf("%v %v", got, err)
	}
}

func TestFieldsTheFileLacksKeepTheirDefaults(t *testing.T) {
	type settings struct {
		Mode string `json:"mode"`
		On   bool   `json:"on"`
	}
	path := filepath.Join(t.TempDir(), "settings.json")
	s := New(path, func() settings { return settings{Mode: "ru_direct", On: true} }, nil, func(string) {})
	// Written by a version that had only the mode.
	if err := os.WriteFile(path, []byte(`{"mode":"global"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got, err := s.ReadStrict(); err != nil || got != (settings{Mode: "global", On: true}) {
		t.Errorf("%+v %v", got, err)
	}
	// A field the file has wins over its default.
	if err := os.WriteFile(path, []byte(`{"mode":"global","on":false}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := s.Read(); got.On {
		t.Errorf("%+v", got)
	}
}

// xorCodec stands in for DPAPI: what it sealed it opens, anything else fails.
type xorCodec struct{}

var sealMark = []byte("sealed:")

func (xorCodec) Seal(p []byte) ([]byte, error) {
	out := append([]byte{}, sealMark...)
	for _, b := range p {
		out = append(out, b^0x5a)
	}
	return out, nil
}

func (xorCodec) Open(s []byte) ([]byte, error) {
	rest, ok := bytes.CutPrefix(s, sealMark)
	if !ok {
		return nil, errors.New("not sealed here")
	}
	out := make([]byte, len(rest))
	for i, b := range rest {
		out[i] = b ^ 0x5a
	}
	return out, nil
}

func TestSealedFiles(t *testing.T) {
	e := newEnv(t)
	if _, err := e.storeWith(xorCodec{}).Update(set(7)); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(e.path)
	if !bytes.HasPrefix(data, sealMark) || bytes.Contains(data, []byte("value")) {
		t.Errorf("not sealed on disk: %q", data)
	}
	if got := e.storeWith(xorCodec{}).Read().Value; got != 7 {
		t.Errorf("value %d", got)
	}
	// A file that cannot be opened here (another PC's) counts as corrupt.
	os.WriteFile(e.path, []byte(`{"value": 3}`), 0o600)
	if _, err := e.storeWith(xorCodec{}).ReadStrict(); !errors.Is(err, ErrCorrupt) {
		t.Errorf("err %v", err)
	}
}
