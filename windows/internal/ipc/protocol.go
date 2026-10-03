// Package ipc is how the window talks to the service: newline-delimited
// JSON over a named pipe. The window sends requests and gets one response
// each; the service pushes events to every window.
package ipc

import "encoding/json"

// Version changes whenever a message changes shape. A window of another
// version asks to be restarted (after an update the old window still runs).
const Version = 3

// Operations the service answers. Nothing takes a file path, a URL to
// run or raw Xray JSON (docs/windows/PLAN.md, section 2.3).
const (
	OpStatus      = "status"
	OpImport      = "import"
	OpConnect     = "connect"
	OpDisconnect  = "disconnect"
	OpSelect      = "select"
	OpRename      = "rename"
	OpDelete      = "delete"
	OpPing        = "ping"
	OpSetSettings = "setSettings"
	OpLogs        = "logs"
	// OpRefresh downloads subscriptions again: the one named, or all.
	OpRefresh = "refresh"
	// OpDeleteSubscription removes a subscription with its servers.
	OpDeleteSubscription = "deleteSubscription"
)

// Events the service pushes. A new connection first gets EventHello, then
// the current status, servers, checks and settings.
const (
	EventHello    = "hello"
	EventStatus   = "status"
	EventProfiles = "profiles"
	EventPings    = "pings"
	EventSettings = "settings"
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
	Network  string `json:"network,omitempty"`
	Security string `json:"security,omitempty"`
	// SubscriptionID names the subscription it came from; empty for an own
	// key.
	SubscriptionID string `json:"subscriptionId,omitempty"`
}

// Subscription is a subscription as the window shows it: no link.
type Subscription struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// UpdatedAt and LastAttemptAt are in Unix milliseconds; 0 is never.
	UpdatedAt     int64  `json:"updatedAt,omitempty"`
	LastAttemptAt int64  `json:"lastAttemptAt,omitempty"`
	LastError     string `json:"lastError,omitempty"`
	// Notice is what the panel said instead of, or besides, servers;
	// Announce is its owner's message.
	Notice   string `json:"notice,omitempty"`
	Announce string `json:"announce,omitempty"`
	// Used and Total are bytes, Expire is in Unix seconds; 0 is unknown.
	Used   int64 `json:"used,omitempty"`
	Total  int64 `json:"total,omitempty"`
	Expire int64 `json:"expire,omitempty"`
	// Updating is true while it is being downloaded.
	Updating bool `json:"updating,omitempty"`
}

// Profiles is the data of EventProfiles.
type Profiles struct {
	Profiles      []Profile      `json:"profiles"`
	Subscriptions []Subscription `json:"subscriptions"`
	SelectedID    string         `json:"selectedId,omitempty"`
}

// ImportArgs carries pasted text: keys, one per line, a message holding
// them, a subscription link or an "Add to Kirov VPN" link.
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

// IDArgs names a server, for OpSelect and OpDelete, or a subscription,
// for OpRefresh (empty: all) and OpDeleteSubscription.
type IDArgs struct {
	ID string `json:"id"`
}

// RenameArgs gives a server a new name.
type RenameArgs struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// PingArgs asks to check servers: those with IDs, or every one when empty.
type PingArgs struct {
	IDs []string `json:"ids,omitempty"`
}

// The states of Ping.
const (
	PingTesting = "testing"
	PingOK      = "ok"
	PingFailed  = "failed"
)

// Ping is the last check of a server: how long a request through it took.
type Ping struct {
	State string `json:"state"`
	Ms    int64  `json:"ms,omitempty"`
}

// Pings is the data of EventPings: the checks by server id.
type Pings map[string]Ping

// The modes of Settings, libxray's routing modes.
const (
	ModeRuDirect    = "ru_direct"
	ModeBlockedOnly = "blocked_only"
	ModeGlobal      = "global"
)

// Settings are the user's choices, the data of EventSettings and the
// argument of OpSetSettings.
type Settings struct {
	// Mode: what goes through the VPN, one of the modes above.
	Mode string `json:"mode"`
	// Sites the user sends directly, through the VPN, or nowhere: domains,
	// addresses or networks, as libxray's user rules read them.
	DirectSites []string `json:"directSites"`
	ProxySites  []string `json:"proxySites"`
	BlockSites  []string `json:"blockSites"`
	// Programs, by file name ("qbittorrent.exe"), that go directly or
	// through the VPN whatever they connect to.
	DirectPrograms []string `json:"directPrograms"`
	ProxyPrograms  []string `json:"proxyPrograms"`
	// TorrentsDirect sends the common torrent clients directly.
	TorrentsDirect bool `json:"torrentsDirect"`
	// AutoConnect brings the VPN back at boot if it was on.
	AutoConnect bool `json:"autoConnect"`
}

// LogSection is one log as the window shows it.
type LogSection struct {
	Title string `json:"title"`
	Text  string `json:"text"`
}

// Logs answers OpLogs: the ends of the service's and the core's logs, with
// addresses and host names taken out.
type Logs struct {
	Sections []LogSection `json:"sections"`
}

// MaxMessage bounds one line on the pipe: an import plus its JSON escaping.
const MaxMessage = 8 * MaxImport
