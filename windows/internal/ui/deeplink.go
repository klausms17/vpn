package ui

import (
	"strings"
	"sync"

	"github.com/klausms17/vpn/libxray/client/linktext"
)

// LinkPrompt is what the window asks about an "Add to Kirov VPN" link:
// the host of the subscription it carries, none for keys. The link itself
// stays here, never in the page.
type LinkPrompt struct {
	Present bool   `json:"present"`
	Host    string `json:"host,omitempty"`
}

// pendingLink keeps the key or subscription link of the last "Add to
// Kirov VPN" link until the user adds it or says no.
type pendingLink struct {
	mu   sync.Mutex
	text string
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
	p.mu.Unlock()
	return p.prompt(), true
}

func (p *pendingLink) prompt() LinkPrompt {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.text == "" {
		return LinkPrompt{}
	}
	return LinkPrompt{Present: true, Host: linktext.URLHost(linktext.SubscriptionURL(p.text))}
}

func (p *pendingLink) peek() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.text
}

// drop forgets text, unless another link came meanwhile.
func (p *pendingLink) drop(text string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.text == text {
		p.text = ""
	}
}
