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
	// settle lets a burst of changes end before the routes are read, at
	// most once per settle; every is how often they are read without one.
	settle, every time.Duration
}

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
		due         time.Time
		shown       string
	)
	schedule := func(d time.Duration) {
		check.Reset(d)
		due = time.Now().Add(d)
	}
	stop := func() {
		check.Stop()
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
			schedule(0)
		case <-changes:
			// One read per settle, however many changes come.
			if watching && time.Until(due) > w.settle {
				schedule(w.settle)
			}
		case <-check.C:
			// Unknown while the tunnel restarts: what was shown stays.
			if found, ok := findConflicts(w.read()); ok {
				if notice := found.notice(); notice != shown {
					shown = notice
					w.log(found.logLine())
					w.report(notice)
				}
			}
			schedule(w.every)
		}
	}
}
