package service

import (
	"encoding/json"
	"maps"
	"sync"
	"time"

	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// pingWorkers is how many servers are checked at once, as on Android: each
// check runs a core of its own.
const pingWorkers = 4

// pingPublishDelay gathers the results that come in close together into
// one event: checking every server would otherwise send the whole list
// once per server.
const pingPublishDelay = 150 * time.Millisecond

// pinger checks how fast servers answer and keeps the last result of each
// for the windows.
type pinger struct {
	// measure runs one check of a server's outbounds and returns the
	// delay in milliseconds.
	measure func(outbounds json.RawMessage) (int64, error)
	publish func(ipc.Pings)

	slots chan struct{}

	mu      sync.Mutex
	pings   ipc.Pings
	pending bool
}

func newPinger(measure func(json.RawMessage) (int64, error), publish func(ipc.Pings)) *pinger {
	return &pinger{measure: measure, publish: publish, slots: make(chan struct{}, pingWorkers), pings: ipc.Pings{}}
}

// current returns the last results.
func (p *pinger) current() ipc.Pings {
	p.mu.Lock()
	defer p.mu.Unlock()
	return maps.Clone(p.pings)
}

// ping checks servers, skipping those being checked already.
func (p *pinger) ping(servers []model.StoredProfile) {
	p.mu.Lock()
	var todo []model.StoredProfile
	for _, s := range servers {
		if p.pings[s.ID].State != ipc.PingTesting {
			p.pings[s.ID] = ipc.Ping{State: ipc.PingTesting}
			todo = append(todo, s)
		}
	}
	p.mu.Unlock()
	if len(todo) == 0 {
		return
	}
	p.publishSoon()
	queue := make(chan model.StoredProfile, len(todo))
	for _, s := range todo {
		queue <- s
	}
	close(queue)
	for range min(pingWorkers, len(todo)) {
		go func() {
			for s := range queue {
				p.slots <- struct{}{}
				p.check(s)
				<-p.slots
			}
		}()
	}
}

func (p *pinger) check(s model.StoredProfile) {
	result := ipc.Ping{State: ipc.PingFailed}
	if ms, err := p.measure(s.Outbounds); err == nil {
		result = ipc.Ping{State: ipc.PingOK, Ms: ms}
	}
	p.mu.Lock()
	// A server deleted meanwhile stays forgotten.
	if _, ok := p.pings[s.ID]; ok {
		p.pings[s.ID] = result
	}
	p.mu.Unlock()
	p.publishSoon()
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
		p.mu.Lock()
		p.pending = false
		pings := maps.Clone(p.pings)
		p.mu.Unlock()
		p.publish(pings)
	})
}
