// Command kirovctl talks to the Kirov VPN service over its pipe, as the
// window does. CI's smoke test drives the service with it; it is never
// shipped.
//
//	kirovctl wait-service    wait until the service answers
//	kirovctl pipe-sddl       print the pipe's owner, permissions and label
//	kirovctl status          print the status
//	kirovctl import          add the keys read from standard input
//	kirovctl connect         connect and wait until connected
//	kirovctl disconnect      disconnect and wait until disconnected
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
		fail(errors.New("usage: kirovctl wait-service | pipe-sddl | status | import | connect | disconnect"))
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	if os.Args[1] == "pipe-sddl" {
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
	}
	if os.Args[1] == "wait-service" {
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
	if err := w.until(ctx, func(ipc.Status) bool { return true }); err != nil {
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
	case "connect":
		if w.status().State != ipc.Connected {
			seen := w.events()
			if err := c.Call(ctx, ipc.OpConnect, nil, nil); err != nil {
				fail(err)
			}
			err := w.until(ctx, func(s ipc.Status) bool {
				return w.events() > seen && (s.State == ipc.Connected || s.State == ipc.Failed)
			})
			if err != nil {
				fail(err)
			}
		}
		print(w.status())
		if w.status().State != ipc.Connected {
			os.Exit(1)
		}
	case "disconnect":
		if err := c.Call(ctx, ipc.OpDisconnect, nil, nil); err != nil {
			fail(err)
		}
		if err := w.until(ctx, func(s ipc.Status) bool { return s.State == ipc.Disconnected }); err != nil {
			fail(err)
		}
		print(w.status())
	default:
		fail(fmt.Errorf("unknown command %q", os.Args[1]))
	}
}

// watch keeps the latest status the service sent.
type watch struct {
	mu      sync.Mutex
	st      *ipc.Status
	n       int
	changed chan struct{}
}

func (w *watch) onEvent(ev ipc.Event) {
	if ev.Event != ipc.EventStatus {
		return
	}
	var s ipc.Status
	if json.Unmarshal(ev.Data, &s) != nil {
		return
	}
	w.mu.Lock()
	w.st, w.n = &s, w.n+1
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

func (w *watch) events() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.n
}

func (w *watch) until(ctx context.Context, ok func(ipc.Status) bool) error {
	for {
		w.mu.Lock()
		st := w.st
		w.mu.Unlock()
		if st != nil && ok(*st) {
			return nil
		}
		select {
		case <-w.changed:
		case <-ctx.Done():
			return fmt.Errorf("timed out; last status: %+v", st)
		}
	}
}

func print(s ipc.Status) {
	out, _ := json.Marshal(s)
	fmt.Println(string(out))
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "kirovctl:", err)
	os.Exit(1)
}
