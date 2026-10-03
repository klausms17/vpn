package service

import (
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeRules struct {
	mu   sync.Mutex
	on   bool
	offs int
	logs []string
}

func (f *fakeRules) hold(backstop time.Duration) *boundedHold {
	return &boundedHold{
		on: func() error {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.on = true
			return nil
		},
		off: func() {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.on = false
			f.offs++
		},
		backstop: backstop,
		log: func(m string) {
			f.mu.Lock()
			defer f.mu.Unlock()
			f.logs = append(f.logs, m)
		},
	}
}

func (f *fakeRules) state() (on bool, offs int, logs string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.on, f.offs, strings.Join(f.logs, "\n")
}

func TestAHoldTheEngineForgetsEndsByItself(t *testing.T) {
	f := &fakeRules{}
	h := f.hold(30 * time.Millisecond)
	if err := h.On(); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond)
	if on, offs, logs := f.state(); on || offs != 1 || !strings.Contains(logs, "outlasted") {
		t.Errorf("on %v, %d offs, logs %q", on, offs, logs)
	}
	// The engine's late end changes nothing.
	h.Off()
	if on, _, _ := f.state(); on {
		t.Error("on again")
	}
}

func TestAHoldTheEngineEndsKeepsNoTimer(t *testing.T) {
	f := &fakeRules{}
	h := f.hold(30 * time.Millisecond)
	h.On()
	h.Off()
	// A later hold lives its own time, not the rest of the first one's.
	time.Sleep(20 * time.Millisecond)
	h.On()
	time.Sleep(20 * time.Millisecond)
	if on, offs, logs := f.state(); !on || offs != 1 || logs != "" {
		t.Errorf("on %v, %d offs, logs %q", on, offs, logs)
	}
	h.Off()
}
