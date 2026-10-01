package ipc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"sync"
)

// Handler answers one request. The error's text goes to the window as it
// is: Russian, and never quoting keys or links. ctx ends when the window
// hangs up.
type Handler func(ctx context.Context, op string, args json.RawMessage) (any, error)

// ErrUnknownOp answers an operation the service does not know.
var ErrUnknownOp = errors.New("неизвестная команда: обновите Kirov VPN")

// Limits, so that no program can stall the service: connections at once
// (a window per signed-in user, with plenty to spare), and per connection
// the requests running (more are told to wait) and the messages queued (a
// window that reads no more is dropped).
const (
	maxConns    = 32
	maxInFlight = 8
	outQueue    = 64
)

// Server answers windows and pushes events to all of them. Requests of one
// connection run concurrently: a slow import must not hold up a disconnect.
type Server struct {
	handle Handler
	// greet gives the events a new connection gets first, the current state.
	greet func() []Event
	log   func(string)

	mu    sync.Mutex
	conns map[*serverConn]struct{}
}

// NewServer returns a server that answers with handle and greets new
// connections with greet's events. greet runs under the server's lock, so
// it may only read state, never broadcast.
func NewServer(handle Handler, greet func() []Event, log func(string)) *Server {
	return &Server{handle: handle, greet: greet, log: log, conns: map[*serverConn]struct{}{}}
}

// Serve accepts connections from l until it is closed.
func (s *Server) Serve(l net.Listener) error {
	slots := make(chan struct{}, maxConns)
	for {
		c, err := l.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		select {
		case slots <- struct{}{}:
			go func() {
				defer func() { <-slots }()
				s.ServeConn(c)
			}()
		default:
			s.log("too many connections, one refused")
			_ = c.Close()
		}
	}
}

// Broadcast pushes ev to every connection.
func (s *Server) Broadcast(ev Event) {
	line, err := encode(ev)
	if err != nil {
		s.log("event not sent: " + err.Error())
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for c := range s.conns {
		c.send(line)
	}
}

// ServeConn talks to one window until it hangs up.
func (s *Server) ServeConn(rw io.ReadWriteCloser) {
	c := &serverConn{rw: rw, out: make(chan []byte, outQueue)}
	go c.write()
	// Under the lock: no broadcast slips in between the greeting and
	// joining, so the window sees every change after the state it got.
	s.mu.Lock()
	greeting := append([]Event{event(EventHello, Hello{Version: Version})}, s.greet()...)
	for _, ev := range greeting {
		if line, err := encode(ev); err == nil {
			c.send(line)
		}
	}
	s.conns[c] = struct{}{}
	s.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	defer func() {
		cancel()
		s.mu.Lock()
		delete(s.conns, c)
		s.mu.Unlock()
		c.close()
	}()
	r := bufio.NewReaderSize(rw, 64<<10)
	slots := make(chan struct{}, maxInFlight)
	for {
		line, err := readLine(r, MaxMessage)
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, net.ErrClosed) && !errors.Is(err, os.ErrClosed) && !errors.Is(err, io.ErrClosedPipe) {
				s.log("window connection: " + err.Error())
			}
			return
		}
		var req Request
		if err := json.Unmarshal(line, &req); err != nil {
			c.respond(Response{Error: "неверный запрос"})
			continue
		}
		select {
		case slots <- struct{}{}:
		default:
			c.respond(Response{ID: req.ID, Error: "Слишком много запросов, подождите"})
			continue
		}
		go func() {
			defer func() { <-slots }()
			c.respond(s.answer(ctx, req))
		}()
	}
}

func (s *Server) answer(ctx context.Context, req Request) (resp Response) {
	resp.ID = req.ID
	defer func() {
		if p := recover(); p != nil {
			s.log("request " + req.Op + " panicked")
			resp = Response{ID: req.ID, Error: "Внутренняя ошибка службы"}
		}
	}()
	result, err := s.handle(ctx, req.Op, req.Args)
	if err != nil {
		resp.Error = err.Error()
		return resp
	}
	if result != nil {
		raw, err := json.Marshal(result)
		if err != nil {
			resp.Error = "Внутренняя ошибка службы"
			return resp
		}
		resp.Result = raw
	}
	return resp
}

type serverConn struct {
	rw     io.ReadWriteCloser
	out    chan []byte
	mu     sync.Mutex
	closed bool
}

func (c *serverConn) respond(resp Response) {
	if line, err := encode(resp); err == nil {
		c.send(line)
	}
}

// send queues line; a window whose queue is full is not reading, and is
// dropped.
func (c *serverConn) send(line []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	select {
	case c.out <- line:
	default:
		c.closeLocked()
	}
}

func (c *serverConn) write() {
	for line := range c.out {
		if _, err := c.rw.Write(line); err != nil {
			c.close()
			// Drain, so senders never block on a dead window.
			for range c.out {
			}
			return
		}
	}
}

func (c *serverConn) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.closed {
		c.closeLocked()
	}
}

func (c *serverConn) closeLocked() {
	c.closed = true
	close(c.out)
	_ = c.rw.Close()
}

func event(name string, data any) Event {
	raw, _ := json.Marshal(data)
	return Event{Event: name, Data: raw}
}

// NewEvent makes the event name carrying data.
func NewEvent(name string, data any) Event { return event(name, data) }

func encode(v any) ([]byte, error) {
	line, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	return append(line, '\n'), nil
}

var errTooLong = errors.New("message too long")

// readLine reads one line of at most max bytes, without its newline.
func readLine(r *bufio.Reader, max int) ([]byte, error) {
	var line []byte
	for {
		chunk, err := r.ReadSlice('\n')
		if len(line)+len(chunk) > max+1 {
			return nil, errTooLong
		}
		line = append(line, chunk...)
		switch {
		case errors.Is(err, bufio.ErrBufferFull):
			continue
		case err != nil:
			return nil, err
		}
		return line[:len(line)-1], nil
	}
}
