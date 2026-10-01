package ipc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"time"
)

// ErrClosed means the connection to the service ended.
var ErrClosed = errors.New("нет связи со службой Kirov VPN")

// writeTimeout bounds one write: a hung service must not hang the window.
const writeTimeout = 10 * time.Second

// RemoteError is an error the service answered with.
type RemoteError struct{ Message string }

func (e *RemoteError) Error() string { return e.Message }

// Client is a window's connection to the service.
type Client struct {
	rw      io.ReadWriteCloser
	onEvent func(Event)

	wmu sync.Mutex // one write at a time

	mu      sync.Mutex
	next    uint64
	pending map[uint64]chan Response
	done    chan struct{}
}

// NewClient talks to the service over rw. onEvent gets every event, in
// order, on the connection's reader goroutine, so it must return quickly.
func NewClient(rw io.ReadWriteCloser, onEvent func(Event)) *Client {
	c := &Client{rw: rw, onEvent: onEvent, pending: map[uint64]chan Response{}, done: make(chan struct{})}
	go c.read()
	return c
}

// Call runs op with args and decodes the answer into result, which may be
// nil. A *RemoteError carries the service's message for the user.
func (c *Client) Call(ctx context.Context, op string, args, result any) error {
	req := Request{Op: op}
	if args != nil {
		raw, err := json.Marshal(args)
		if err != nil {
			return err
		}
		req.Args = raw
	}
	ch := make(chan Response, 1)
	c.mu.Lock()
	select {
	case <-c.done:
		c.mu.Unlock()
		return ErrClosed
	default:
	}
	c.next++
	req.ID = c.next
	c.pending[req.ID] = ch
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		delete(c.pending, req.ID)
		c.mu.Unlock()
	}()

	if err := c.write(req); err != nil {
		c.Close()
		return ErrClosed
	}
	select {
	case resp := <-ch:
		if resp.Error != "" {
			return &RemoteError{Message: resp.Error}
		}
		if result != nil && len(resp.Result) > 0 {
			return json.Unmarshal(resp.Result, result)
		}
		return nil
	case <-c.done:
		return ErrClosed
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Done is closed once the connection has ended.
func (c *Client) Done() <-chan struct{} { return c.done }

// Close ends the connection.
func (c *Client) Close() error { return c.rw.Close() }

func (c *Client) write(req Request) error {
	line, err := encode(req)
	if err != nil {
		return err
	}
	c.wmu.Lock()
	defer c.wmu.Unlock()
	if d, ok := c.rw.(interface{ SetWriteDeadline(time.Time) error }); ok {
		_ = d.SetWriteDeadline(time.Now().Add(writeTimeout))
	}
	_, err = c.rw.Write(line)
	return err
}

func (c *Client) read() {
	defer func() {
		c.mu.Lock()
		close(c.done)
		c.mu.Unlock()
		_ = c.rw.Close()
	}()
	r := bufio.NewReaderSize(c.rw, 64<<10)
	for {
		line, err := readLine(r, MaxMessage)
		if err != nil {
			return
		}
		var msg struct {
			Response
			Event
		}
		if err := json.Unmarshal(line, &msg); err != nil {
			return
		}
		if msg.Event.Event != "" {
			c.onEvent(msg.Event)
			continue
		}
		c.mu.Lock()
		ch := c.pending[msg.ID]
		c.mu.Unlock()
		if ch != nil {
			select {
			case ch <- msg.Response:
			default: // a second answer to one request
			}
		}
	}
}
