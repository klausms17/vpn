package ui

import (
	"strings"
	"sync"

	"github.com/klausms17/vpn/libxray/client/linktext"
)

// LinkPrompt is what the window asks about an "Add to Kirov VPN" link:
// the host of the subscription it carries, none for keys, and its number,
// which the answer names. The link itself stays here, never in the page.
type LinkPrompt struct {
	Present bool   `json:"present"`
	Seq     uint64 `json:"seq"`
	Host    string `json:"host,omitempty"`
}

// pendingLink keeps the key or subscription link of the last "Add to
// Kirov VPN" link until the user adds it or says no. Each link gets the
// next number, so an answer about one never adds or drops a newer one.
type pendingLink struct {
	mu   sync.Mutex
	text string
	seq  uint64
}

// offer keeps what arg carries when it is such a link; ok tells it was.
func (p *pendingLink) offer(arg string) (prompt LinkPrompt, ok bool) {
	if !strings.HasPrefix(strings.ToLower(strings.TrimSpace(arg)), "klausvpn:") {
		return LinkPrompt{}, false
	}
	text := linktext.DeepLink(arg)
	if text == "" {
		return LinkPrompt{}, false
	}
	p.mu.Lock()
	p.text = text
	p.seq++
	p.mu.Unlock()
	return p.prompt(), true
}

func (p *pendingLink) prompt() LinkPrompt {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.text == "" {
		return LinkPrompt{}
	}
	return LinkPrompt{Present: true, Seq: p.seq, Host: linktext.URLHost(linktext.SubscriptionURL(p.text))}
}

// peek returns link seq while it waits, else "".
func (p *pendingLink) peek(seq uint64) string {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.seq != seq {
		return ""
	}
	return p.text
}

// drop forgets link seq, unless another came meanwhile.
func (p *pendingLink) drop(seq uint64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.seq == seq {
		p.text = ""
	}
}
