package ipc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

type recorder struct {
	mu     sync.Mutex
	events []Event
	got    chan struct{}
}

func newRecorder() *recorder { return &recorder{got: make(chan struct{}, 1000)} }

func (r *recorder) add(ev Event) {
	r.mu.Lock()
	r.events = append(r.events, ev)
	r.mu.Unlock()
	r.got <- struct{}{}
}

func (r *recorder) wait(t *testing.T, n int) []Event {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		r.mu.Lock()
		if len(r.events) >= n {
			evs := append([]Event(nil), r.events...)
			r.mu.Unlock()
			return evs
		}
		r.mu.Unlock()
		select {
		case <-r.got:
		case <-deadline:
			t.Fatalf("got %d events, want %d", len(r.events), n)
		}
	}
}

func silent(string) {}

func greeting() []Event { return []Event{NewEvent(EventStatus, Status{State: Connected})} }

func connect(t *testing.T, s *Server) (*Client, *recorder) {
	t.Helper()
	a, b := net.Pipe()
	go s.ServeConn(a)
	rec := newRecorder()
	c := NewClient(b, rec.add)
	t.Cleanup(func() { c.Close() })
	return c, rec
}

func echo(_ context.Context, op string, args json.RawMessage) (any, error) {
	switch op {
	case "echo":
		var v ImportArgs
		if err := json.Unmarshal(args, &v); err != nil {
			return nil, err
		}
		return ImportResult{Message: v.Text}, nil
	case "fail":
		return nil, errors.New("Не выбран сервер")
	case "panic":
		panic("boom")
	}
	return nil, ErrUnknownOp
}

func TestAWindowIsGreetedThenAnswered(t *testing.T) {
	c, rec := connect(t, NewServer(echo, greeting, silent))
	evs := rec.wait(t, 2)
	var h Hello
	if evs[0].Event != EventHello || json.Unmarshal(evs[0].Data, &h) != nil || h.Version != Version {
		t.Errorf("first event %+v", evs[0])
	}
	if evs[1].Event != EventStatus {
		t.Errorf("second event %+v", evs[1])
	}
	var res ImportResult
	if err := c.Call(context.Background(), "echo", ImportArgs{Text: "привет\n"}, &res); err != nil || res.Message != "привет\n" {
		t.Errorf("%+v %v", res, err)
	}
}

func TestErrorsReachTheWindowAsTheyAre(t *testing.T) {
	c, _ := connect(t, NewServer(echo, greeting, silent))
	var re *RemoteError
	if err := c.Call(context.Background(), "fail", nil, nil); !errors.As(err, &re) || re.Message != "Не выбран сервер" {
		t.Errorf("err %v", err)
	}
	if err := c.Call(context.Background(), "nope", nil, nil); err == nil || err.Error() != ErrUnknownOp.Error() {
		t.Errorf("unknown op: %v", err)
	}
	// A panicking request is answered, and the connection keeps working.
	if err := c.Call(context.Background(), "panic", nil, nil); err == nil || !strings.Contains(err.Error(), "Внутренняя ошибка") {
		t.Errorf("panic: %v", err)
	}
	if err := c.Call(context.Background(), "echo", ImportArgs{Text: "x"}, nil); err != nil {
		t.Errorf("after the panic: %v", err)
	}
}

func TestBroadcastReachesEveryWindowInOrder(t *testing.T) {
	s := NewServer(echo, greeting, silent)
	_, rec1 := connect(t, s)
	_, rec2 := connect(t, s)
	rec1.wait(t, 2)
	rec2.wait(t, 2)
	for i := range 20 {
		s.Broadcast(NewEvent(EventStatus, Status{State: i % 5, Message: fmt.Sprint(i)}))
	}
	for _, rec := range []*recorder{rec1, rec2} {
		evs := rec.wait(t, 22)
		for i, ev := range evs[2:] {
			var st Status
			if json.Unmarshal(ev.Data, &st) != nil || st.Message != fmt.Sprint(i) {
				t.Fatalf("event %d: %s", i, ev.Data)
			}
		}
	}
}

func TestASlowRequestDoesNotHoldUpOthers(t *testing.T) {
	release := make(chan struct{})
	handle := func(ctx context.Context, op string, args json.RawMessage) (any, error) {
		if op == "slow" {
			<-release
		}
		return op, nil
	}
	c, _ := connect(t, NewServer(handle, greeting, silent))
	slow := make(chan error, 1)
	go func() { slow <- c.Call(context.Background(), "slow", nil, nil) }()
	var got string
	if err := c.Call(context.Background(), "fast", nil, &got); err != nil || got != "fast" {
		t.Fatalf("%q %v", got, err)
	}
	select {
	case <-slow:
		t.Fatal("slow request finished early")
	default:
	}
	close(release)
	if err := <-slow; err != nil {
		t.Error(err)
	}
}

func TestTooManyRequestsAreTurnedAway(t *testing.T) {
	release := make(chan struct{})
	started := make(chan struct{}, maxInFlight)
	handle := func(ctx context.Context, op string, args json.RawMessage) (any, error) {
		started <- struct{}{}
		<-release
		return nil, nil
	}
	c, _ := connect(t, NewServer(handle, greeting, silent))
	var wg sync.WaitGroup
	for range maxInFlight {
		wg.Go(func() { c.Call(context.Background(), "slow", nil, nil) })
	}
	for range maxInFlight {
		<-started
	}
	err := c.Call(context.Background(), "slow", nil, nil)
	if err == nil || !strings.Contains(err.Error(), "Слишком много") {
		t.Errorf("err %v", err)
	}
	close(release)
	wg.Wait()
}

// waitConns waits until s serves n connections.
func waitConns(t *testing.T, s *Server, n int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		s.mu.Lock()
		got := len(s.conns)
		s.mu.Unlock()
		if got == n {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%d connections, want %d", got, n)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestAWindowThatStopsReadingIsDropped(t *testing.T) {
	s := NewServer(echo, greeting, silent)
	a, b := net.Pipe()
	go s.ServeConn(a)
	// Joined, so the broadcasts below are queued for it.
	waitConns(t, s, 1)
	done := make(chan struct{})
	go func() {
		for range outQueue * 3 {
			s.Broadcast(NewEvent(EventStatus, Status{}))
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("broadcast blocked on a window that does not read")
	}
	// The service hung up on it.
	b.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 1<<20)
	for {
		if _, err := b.Read(buf); err != nil {
			if errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "timeout") {
				t.Fatal("still connected")
			}
			break
		}
	}
	waitConns(t, s, 0)
}

func TestAnOverlongMessageEndsTheConnection(t *testing.T) {
	s := NewServer(echo, greeting, silent)
	a, b := net.Pipe()
	go s.ServeConn(a)
	rec := newRecorder()
	c := NewClient(b, rec.add)
	defer c.Close()
	rec.wait(t, 2)
	err := c.Call(context.Background(), "echo", ImportArgs{Text: strings.Repeat("я", MaxMessage)}, nil)
	if !errors.Is(err, ErrClosed) {
		t.Errorf("err %v", err)
	}
	select {
	case <-c.Done():
	case <-time.After(5 * time.Second):
		t.Error("still connected")
	}
}

func TestCallsEndWithTheConnection(t *testing.T) {
	block := make(chan struct{})
	defer close(block)
	handle := func(ctx context.Context, op string, args json.RawMessage) (any, error) {
		<-block
		return nil, nil
	}
	a, b := net.Pipe()
	go NewServer(handle, greeting, silent).ServeConn(a)
	c := NewClient(b, func(Event) {})
	pending := make(chan error, 1)
	go func() { pending <- c.Call(context.Background(), "slow", nil, nil) }()
	time.Sleep(50 * time.Millisecond)
	a.Close() // the service went away
	if err := <-pending; !errors.Is(err, ErrClosed) {
		t.Errorf("pending call: %v", err)
	}
	if err := c.Call(context.Background(), "x", nil, nil); !errors.Is(err, ErrClosed) {
		t.Errorf("later call: %v", err)
	}

	// A cancelled call returns at once.
	a2, b2 := net.Pipe()
	go NewServer(handle, greeting, silent).ServeConn(a2)
	c2 := NewClient(b2, func(Event) {})
	defer c2.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if err := c2.Call(ctx, "slow", nil, nil); !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("cancelled call: %v", err)
	}
}

func TestGarbageIsAnsweredNotFatal(t *testing.T) {
	s := NewServer(echo, greeting, silent)
	a, b := net.Pipe()
	go s.ServeConn(a)
	rec := newRecorder()
	go func() {
		b.Write([]byte("not json\n"))
	}()
	c := NewClient(b, rec.add)
	defer c.Close()
	if err := c.Call(context.Background(), "echo", ImportArgs{Text: "ok"}, nil); err != nil {
		t.Errorf("after garbage: %v", err)
	}
}

func TestConnectionsBeyondTheLimitAreRefused(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	var logged sync.Map
	s := NewServer(echo, greeting, func(m string) { logged.Store(m, true) })
	go s.Serve(l)
	var clients []*Client
	defer func() {
		for _, c := range clients {
			c.Close()
		}
	}()
	for range maxConns {
		conn, err := net.Dial("tcp", l.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		rec := newRecorder()
		c := NewClient(conn, rec.add)
		clients = append(clients, c)
		rec.wait(t, 2)
	}
	conn, err := net.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c := NewClient(conn, func(Event) {})
	select {
	case <-c.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("one connection too many was served")
	}
	if _, ok := logged.Load("too many connections, one refused"); !ok {
		t.Error("not logged")
	}
	// A window that goes frees its place.
	clients[0].Close()
	time.Sleep(100 * time.Millisecond)
	conn, err = net.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	rec := newRecorder()
	clients = append(clients, NewClient(conn, rec.add))
	rec.wait(t, 2)
}
