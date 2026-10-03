package subscription

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/model"
)

// Pinned holds the outbounds of saved servers whose certificate was pinned,
// by link without "#name" (panels change names, e.g. "12 days left") and
// by endpoint.
type Pinned struct {
	byLink, byEndpoint map[string]json.RawMessage
}

// PinnedOf indexes servers; the last of two alike wins.
func PinnedOf(servers []model.StoredProfile) Pinned {
	p := Pinned{byLink: map[string]json.RawMessage{}, byEndpoint: map[string]json.RawMessage{}}
	for _, s := range servers {
		if s.Link != "" {
			p.byLink[linkKey(s.Link)] = s.Outbounds
		}
		p.byEndpoint[endpointKey(s.Protocol, s.Address, s.Port)] = s.Outbounds
	}
	return p
}

// SameLink returns the outbounds of the same link: the same server
// settings, safe to reuse without contacting the server.
func (p Pinned) SameLink(x libxray.Profile) json.RawMessage {
	if x.Link == "" {
		return nil
	}
	return p.byLink[linkKey(x.Link)]
}

// SameServer returns the outbounds of the same server, maybe with other
// settings: only for when it cannot be pinned now.
func (p Pinned) SameServer(x libxray.Profile) json.RawMessage {
	if out := p.SameLink(x); out != nil {
		return out
	}
	return p.byEndpoint[endpointKey(x.Protocol, x.Address, x.Port)]
}

func linkKey(link string) string {
	key, _, _ := strings.Cut(link, "#")
	return key
}

func endpointKey(protocol, address string, port int) string {
	return fmt.Sprintf("%s|%s|%d", protocol, strings.ToLower(address), port)
}
