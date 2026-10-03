// Command kirovctl talks to the Kirov VPN service over its pipe, as the
// window does. CI's smoke test drives the service with it; it is never
// shipped.
//
//	kirovctl wait-service    wait until the service answers
//	kirovctl pipe-sddl       print the pipe's owner, permissions and label
//	kirovctl status          print the status
//	kirovctl import          add the keys or subscription read from standard input
//	kirovctl refresh         download the subscriptions again
//	kirovctl connect         connect and wait until connected
//	kirovctl wait-connected  wait until the tunnel is up
//	kirovctl disconnect      disconnect and wait until disconnected
//	kirovctl ping            check every server and print the results
//	kirovctl set-settings    save the settings read from standard input
//	kirovctl logs            print the journal
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"
	"time"

	"github.com/klausms17/vpn/windows/internal/ipc"
	"golang.org/x/sys/windows"
)

const timeout = 90 * time.Second

func main() {
	if len(os.Args) != 2 {
		fail(errors.New("usage: kirovctl wait-service | pipe-sddl | status | import | refresh | connect | wait-connected | disconnect | ping | set-settings | logs"))
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	switch os.Args[1] {
	case "pipe-sddl":
		conn, err := ipc.Dial(ctx)
		if err != nil {
			fail(err)
		}
		defer conn.Close()
		sd, err := windows.GetSecurityInfo(windows.Handle(conn.Fd()), windows.SE_FILE_OBJECT,
			windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION|windows.LABEL_SECURITY_INFORMATION)
		if err != nil {
			fail(err)
		}
		fmt.Println(sd.String())
		return
	case "wait-service":
		for {
			if conn, err := ipc.Dial(ctx); err == nil {
				conn.Close()
				fmt.Println("service is up")
				return
			}
			select {
			case <-ctx.Done():
				fail(errors.New("the service did not come up"))
			case <-time.After(500 * time.Millisecond):
			}
		}
	}
	w := &watch{changed: make(chan struct{}, 1)}
	conn, err := ipc.Dial(ctx)
	if err != nil {
		fail(err)
	}
	c := ipc.NewClient(conn, w.onEvent)
	defer c.Close()
	// The greeting carries the status.
	if err := w.until(ctx, func(*watch) bool { return w.st != nil }); err != nil {
		fail(err)
	}
	switch os.Args[1] {
	case "status":
		print(w.status())
	case "import":
		text, err := io.ReadAll(io.LimitReader(os.Stdin, ipc.MaxImport+1))
		if err != nil {
			fail(err)
		}
		var r ipc.ImportResult
		if err := c.Call(ctx, ipc.OpImport, ipc.ImportArgs{Text: string(text)}, &r); err != nil {
			fail(err)
		}
		fmt.Println(r.Message)
		if r.Added == 0 {
			os.Exit(1)
		}
	case "refresh":
		var r ipc.ImportResult
		if err := c.Call(ctx, ipc.OpRefresh, ipc.IDArgs{}, &r); err != nil {
			fail(err)
		}
		fmt.Println(r.Message)
	case "connect":
		if w.status().State != ipc.Connected {
			seen := w.statuses()
			if err := c.Call(ctx, ipc.OpConnect, nil, nil); err != nil {
				fail(err)
			}
			err := w.until(ctx, func(w *watch) bool {
				return w.n > seen && (w.st.State == ipc.Connected || w.st.State == ipc.Failed)
			})
			if err != nil {
				fail(err)
			}
		}
		print(w.status())
		if w.status().State != ipc.Connected {
			os.Exit(1)
		}
	case "wait-connected":
		if err := w.until(ctx, func(w *watch) bool { return w.st.State == ipc.Connected && w.st.Message == "" }); err != nil {
			fail(err)
		}
		print(w.status())
	case "disconnect":
		if err := c.Call(ctx, ipc.OpDisconnect, nil, nil); err != nil {
			fail(err)
		}
		if err := w.until(ctx, func(w *watch) bool { return w.st.State == ipc.Disconnected }); err != nil {
			fail(err)
		}
		print(w.status())
	case "ping":
		seen := w.pingEvents()
		if err := c.Call(ctx, ipc.OpPing, ipc.PingArgs{}, nil); err != nil {
			fail(err)
		}
		err := w.until(ctx, func(w *watch) bool {
			if w.np <= seen || len(w.pings) == 0 {
				return false
			}
			for _, p := range w.pings {
				if p.State == ipc.PingTesting {
					return false
				}
			}
			return true
		})
		if err != nil {
			fail(err)
		}
		print(w.lastPings())
	case "set-settings":
		var s ipc.Settings
		if err := json.NewDecoder(io.LimitReader(os.Stdin, 1<<20)).Decode(&s); err != nil {
			fail(err)
		}
		if err := c.Call(ctx, ipc.OpSetSettings, s, nil); err != nil {
			fail(err)
		}
		fmt.Println("saved")
	case "logs":
		var l ipc.Logs
		if err := c.Call(ctx, ipc.OpLogs, nil, &l); err != nil {
			fail(err)
		}
		for _, s := range l.Sections {
			fmt.Printf("=== %s ===\n%s\n", s.Title, s.Text)
		}
	default:
		fail(fmt.Errorf("unknown command %q", os.Args[1]))
	}
}

// watch keeps the latest status and server checks the service sent.
type watch struct {
	mu      sync.Mutex
	st      *ipc.Status
	n       int
	pings   ipc.Pings
	np      int
	changed chan struct{}
}

func (w *watch) onEvent(ev ipc.Event) {
	w.mu.Lock()
	switch ev.Event {
	case ipc.EventStatus:
		var s ipc.Status
		if json.Unmarshal(ev.Data, &s) == nil {
			w.st, w.n = &s, w.n+1
		}
	case ipc.EventPings:
		var p ipc.Pings
		if json.Unmarshal(ev.Data, &p) == nil {
			w.pings, w.np = p, w.np+1
		}
	}
	w.mu.Unlock()
	select {
	case w.changed <- struct{}{}:
	default:
	}
}

func (w *watch) status() ipc.Status {
	w.mu.Lock()
	defer w.mu.Unlock()
	return *w.st
}

func (w *watch) statuses() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.n
}

func (w *watch) pingEvents() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.np
}

func (w *watch) lastPings() ipc.Pings {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.pings
}

// until waits until ok holds, which it tests under the watch's lock.
func (w *watch) until(ctx context.Context, ok func(*watch) bool) error {
	for {
		w.mu.Lock()
		done := w.st != nil && ok(w)
		last := w.st
		w.mu.Unlock()
		if done {
			return nil
		}
		select {
		case <-w.changed:
		case <-ctx.Done():
			return fmt.Errorf("timed out; last status: %+v", last)
		}
	}
}

func print(v any) {
	out, _ := json.Marshal(v)
	fmt.Println(string(out))
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "kirovctl:", err)
	os.Exit(1)
}
