package service

import (
	"maps"
	"slices"
	"sync"
	"time"

	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// pingParallel is how many servers a batch checks at once, as on Android.
const pingParallel = 4

// pingPublishDelay gathers results that come in close together into one
// event.
const pingPublishDelay = 150 * time.Millisecond

// pinger checks how fast servers answer and keeps the last result of each
// for the windows. A batch of checks is one temporary core, which takes
// over Xray's process-wide state for a moment; two at once would take it
// from each other, so servers asked for while a batch runs wait for the
// next one.
type pinger struct {
	// probe checks servers and returns the delay of each in milliseconds,
	// or -1, in their order.
	probe func([]model.StoredProfile) []int64
	// saved returns the servers as saved now: a server deleted before its
	// batch is not checked.
	saved   func() (model.ProfilesState, error)
	publish func(ipc.Pings)

	mu      sync.Mutex
	pings   ipc.Pings
	queue   []string
	running bool
	pending bool

	// publishing keeps the events in the order their results were taken.
	publishing sync.Mutex
}

func newPinger(probe func([]model.StoredProfile) []int64, saved func() (model.ProfilesState, error), publish func(ipc.Pings)) *pinger {
	return &pinger{probe: probe, saved: saved, publish: publish, pings: ipc.Pings{}}
}

// current returns the last results.
func (p *pinger) current() ipc.Pings {
	p.mu.Lock()
	defer p.mu.Unlock()
	return maps.Clone(p.pings)
}

// ping checks the saved servers with ids, or all of them when ids is
// empty, skipping those being checked already.
func (p *pinger) ping(ids []string) error {
	saved, err := p.saved()
	if err != nil {
		return err
	}
	p.mu.Lock()
	added := false
	for _, s := range saved.Profiles {
		if (len(ids) == 0 || slices.Contains(ids, s.ID)) && p.pings[s.ID].State != ipc.PingTesting {
			p.pings[s.ID] = ipc.Ping{State: ipc.PingTesting}
			p.queue = append(p.queue, s.ID)
			added = true
		}
	}
	start := added && !p.running
	p.running = p.running || start
	p.mu.Unlock()
	if added {
		p.publishSoon()
	}
	if start {
		go p.run()
	}
	return nil
}

// run checks the queued servers, batch after batch, until none is left.
func (p *pinger) run() {
	for {
		saved, err := p.saved()
		p.mu.Lock()
		queued := p.queue
		p.queue = nil
		var batch []model.StoredProfile
		for _, id := range queued {
			i := slices.IndexFunc(saved.Profiles, func(s model.StoredProfile) bool { return s.ID == id })
			switch {
			case err != nil:
				p.pings[id] = ipc.Ping{State: ipc.PingFailed}
			case i < 0:
				delete(p.pings, id)
			default:
				batch = append(batch, saved.Profiles[i])
			}
		}
		if len(batch) == 0 {
			p.running = false
			p.mu.Unlock()
			if len(queued) > 0 {
				p.publishSoon()
			}
			return
		}
		p.mu.Unlock()
		results := p.probe(batch)
		p.mu.Lock()
		for i, s := range batch {
			// A server deleted meanwhile stays forgotten.
			if _, ok := p.pings[s.ID]; !ok {
				continue
			}
			if ms := results[i]; ms >= 0 {
				p.pings[s.ID] = ipc.Ping{State: ipc.PingOK, Ms: ms}
			} else {
				p.pings[s.ID] = ipc.Ping{State: ipc.PingFailed}
			}
		}
		p.mu.Unlock()
		p.publishSoon()
	}
}

// keep forgets the results of servers that are not in ids any more.
func (p *pinger) keep(ids map[string]bool) {
	p.mu.Lock()
	changed := false
	for id := range p.pings {
		if !ids[id] {
			delete(p.pings, id)
			changed = true
		}
	}
	p.mu.Unlock()
	if changed {
		p.publishSoon()
	}
}

func (p *pinger) publishSoon() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.pending {
		return
	}
	p.pending = true
	time.AfterFunc(pingPublishDelay, func() {
		p.publishing.Lock()
		defer p.publishing.Unlock()
		p.mu.Lock()
		p.pending = false
		pings := maps.Clone(p.pings)
		p.mu.Unlock()
		p.publish(pings)
	})
}
