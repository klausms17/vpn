package service

import (
	"context"
	"sync/atomic"
	"time"
)

// conflictWatch looks, while the tunnel is up, for other VPNs whose routes
// take its traffic (findConflicts) and reports them while that lasts. It
// changes nothing: another program's routes are its own, and taking them
// away would fight it and could break the PC's network.
type conflictWatch struct {
	conflictDeps
	up   atomic.Bool
	wake chan struct{}
}

// conflictDeps is what the watch works with.
type conflictDeps struct {
	// read reads the routes: none while the tunnel's adapter is missing or
	// the routes cannot be read.
	read func() routeTable
	// subscribe calls changed on each change of Windows' routes or
	// interfaces, until unsubscribe.
	subscribe func(changed func()) (unsubscribe func(), err error)
	// report gets the window's notice, "" when it is over.
	report func(notice string)
	log    func(string)
	// settle is how long the changes must pause before the routes are
	// read; every is how often they are read without a change.
	settle, every time.Duration
}

// A burst of changes that goes on is read every burstSettles settles all
// the same.
const burstSettles = 5

func newConflictWatch(d conflictDeps) *conflictWatch {
	return &conflictWatch{conflictDeps: d, wake: make(chan struct{}, 1)}
}

// tunnel tells whether the tunnel is up. It never blocks.
func (w *conflictWatch) tunnel(up bool) {
	if w.up.Swap(up) != up {
		select {
		case w.wake <- struct{}{}:
		default:
		}
	}
}

// run watches until ctx ends.
func (w *conflictWatch) run(ctx context.Context) {
	changes := make(chan struct{}, 1)
	changed := func() {
		select {
		case changes <- struct{}{}:
		default:
		}
	}
	check := time.NewTimer(time.Hour)
	check.Stop()
	var (
		watching    bool
		unsubscribe func()
		// burst is when the changes not read yet began.
		burst time.Time
		shown string
	)
	stop := func() {
		check.Stop()
		burst = time.Time{}
		if unsubscribe != nil {
			unsubscribe()
			unsubscribe = nil
		}
	}
	defer stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-w.wake:
			up := w.up.Load()
			if up == watching {
				continue
			}
			watching = up
			if !up {
				stop()
				if shown != "" {
					shown = ""
					w.report("")
				}
				continue
			}
			var err error
			if unsubscribe, err = w.subscribe(changed); err != nil {
				w.log("route changes cannot be watched, only checked now and then: " + err.Error())
			}
			check.Reset(0)
		case <-changes:
			if !watching {
				continue
			}
			// Read once the changes pause: amid them, as when the tunnel
			// restarts and Xray sets its routes again, it may have only
			// some of them.
			now := time.Now()
			if burst.IsZero() {
				burst = now
			}
			check.Reset(min(w.settle, burst.Add(burstSettles*w.settle).Sub(now)))
		case <-check.C:
			burst = time.Time{}
			// Unknown while the tunnel restarts: what was shown stays.
			if found, ok := findConflicts(w.read()); ok {
				if notice := found.notice(); notice != shown {
					shown = notice
					w.log(found.logLine())
					w.report(notice)
				}
			}
			check.Reset(w.every)
		}
	}
}
