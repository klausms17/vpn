// Package ipc is how the window talks to the service: newline-delimited
// JSON over a named pipe. The window sends requests and gets one response
// each; the service pushes events to every window.
package ipc

import "encoding/json"

// Version changes whenever a message changes shape. A window of another
// version asks to be restarted (after an update the old window still runs).
const Version = 1

// Operations the service answers. Nothing takes a file path, a URL to
// run or raw Xray JSON (docs/windows/PLAN.md, section 2.3).
const (
	OpStatus     = "status"
	OpImport     = "import"
	OpConnect    = "connect"
	OpDisconnect = "disconnect"
)

// Events the service pushes. A new connection first gets EventHello, then
// the current status and servers.
const (
	EventHello    = "hello"
	EventStatus   = "status"
	EventProfiles = "profiles"
)

// Request is one call from a window.
type Request struct {
	ID   uint64          `json:"id"`
	Op   string          `json:"op"`
	Args json.RawMessage `json:"args,omitempty"`
}

// Response answers the request with the same ID. Error is a Russian text
// the window may show as it is.
type Response struct {
	ID     uint64          `json:"id"`
	Error  string          `json:"error,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
}

// Event is pushed to every window.
type Event struct {
	Event string          `json:"event"`
	Data  json.RawMessage `json:"data,omitempty"`
}

// Hello is the data of EventHello.
type Hello struct {
	Version int `json:"version"`
}

// The states of Status, the numbers of Android's VpnState.
const (
	Disconnected  = 0
	Connecting    = 1
	Connected     = 2
	Disconnecting = 3
	Failed        = 4
)

// Status is what Android's IVpnCallback.onStatus carries.
type Status struct {
	State       int    `json:"state"`
	ProfileID   string `json:"profileId,omitempty"`
	ProfileName string `json:"profileName,omitempty"`
	// Message is the error, or a notice while connected.
	Message string `json:"message,omitempty"`
	// ConnectedSince is in Unix milliseconds.
	ConnectedSince int64 `json:"connectedSince,omitempty"`
}

// Profile is one saved server as the window lists it: no keys, no links.
type Profile struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Protocol string `json:"protocol"`
}

// Profiles is the data of EventProfiles.
type Profiles struct {
	Profiles   []Profile `json:"profiles"`
	SelectedID string    `json:"selectedId,omitempty"`
}

// ImportArgs carries pasted text: keys, one per line, or a message
// holding them.
type ImportArgs struct {
	Text string `json:"text"`
}

// ImportResult says what an import did, in a message for the user.
type ImportResult struct {
	Added   int    `json:"added"`
	Message string `json:"message"`
}

// MaxImport is the most text an import takes, Android's limit.
const MaxImport = 256 << 10

// MaxMessage bounds one line on the pipe: an import plus its JSON escaping.
const MaxMessage = 8 * MaxImport
