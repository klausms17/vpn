package subscription

import (
	"cmp"
	"encoding/json"
	"fmt"
	"reflect"
	"slices"
	"strings"

	"github.com/klausms17/vpn/libxray/client/model"
)

const (
	// StaleMs: a subscription without a fresh list for this long is
	// refreshed by itself.
	StaleMs = 60 * 60_000
	// RetryMs: automatic attempts at most this often, whatever the result.
	RetryMs = 2 * 60_000
)

// IsStale tells whether sub is worth refreshing now: no fresh list for an
// hour and no attempt in the last minutes. A clock set back counts as old.
func IsStale(sub model.Subscription, now int64) bool {
	old := now-sub.UpdatedAt >= StaleMs || sub.UpdatedAt > now
	rested := now-sub.LastAttemptAt >= RetryMs || sub.LastAttemptAt > now
	return old && rested
}

// Merge applies f to subscription subID in state. Servers that are still
// there keep their ids, so the selection, checks and the running tunnel's
// identity survive; names and settings come from the panel. Without
// servers in f only the subscription's details change. A subscription
// deleted while it was downloading is not brought back. The subscription's
// servers come after the others, as many as fit under model.MaxProfiles.
func Merge(state model.ProfilesState, subID string, f Fetched, now int64, newID func() string) model.ProfilesState {
	i := slices.IndexFunc(state.Subscriptions, func(s model.Subscription) bool { return s.ID == subID })
	if i < 0 {
		return state
	}
	applied := len(f.Keys) > 0
	sub := state.Subscriptions[i]
	sub.LastAttemptAt = now
	sub.LastError = ""
	sub.Notice = f.Notice
	sub.Announce = f.Announce
	sub.SupportURL = f.SupportURL
	if applied {
		sub.UpdatedAt = now
		sub.UserInfo, sub.ReportURL, sub.AppURL = f.UserInfo, f.ReportURL, f.AppURL
	} else {
		// An answer without servers keeps what it does not bring.
		sub.UserInfo = cmp.Or(f.UserInfo, sub.UserInfo)
		sub.ReportURL = cmp.Or(f.ReportURL, sub.ReportURL)
		sub.AppURL = cmp.Or(f.AppURL, sub.AppURL)
	}
	state.Subscriptions = slices.Clone(state.Subscriptions)
	state.Subscriptions[i] = sub
	if !applied {
		return state
	}

	var old, others []model.StoredProfile
	for _, p := range state.Profiles {
		if p.SubscriptionID == subID {
			old = append(old, p)
		} else {
			others = append(others, p)
		}
	}
	keys := f.Keys[:min(len(f.Keys), max(model.MaxProfiles-len(others), 0))]
	matches := matchExisting(old, keys)
	stored := make([]model.StoredProfile, len(keys))
	for i, k := range keys {
		id, createdAt := "", now
		if m := matches[i]; m != nil {
			id, createdAt = m.ID, m.CreatedAt
		} else {
			id = newID()
		}
		stored[i] = k.Stored(id, subID, createdAt)
	}
	all := append(others, stored...)
	if !slices.ContainsFunc(all, func(p model.StoredProfile) bool { return p.ID == state.SelectedID }) {
		// The selected server left the subscription, or none was selected.
		state.SelectedID = ""
		if len(stored) > 0 {
			state.SelectedID = stored[0].ID
		} else if len(others) > 0 {
			state.SelectedID = others[0].ID
		}
	}
	state.Profiles = all
	return state
}

// MarkFailed records a failed attempt; the servers stay as they are.
func MarkFailed(state model.ProfilesState, subID, err string, now int64) model.ProfilesState {
	state.Subscriptions = slices.Clone(state.Subscriptions)
	for i := range state.Subscriptions {
		if state.Subscriptions[i].ID == subID {
			state.Subscriptions[i].LastError = clip(err, maxPanelText)
			state.Subscriptions[i].LastAttemptAt = now
		}
	}
	return state
}

// RunningChanged tells whether runningID, a server of subID, has other
// outbounds after than before, or is gone: the tunnel should restart.
func RunningChanged(before, after model.ProfilesState, subID, runningID string) bool {
	i := slices.IndexFunc(before.Profiles, func(p model.StoredProfile) bool {
		return p.ID == runningID && p.SubscriptionID == subID
	})
	if i < 0 {
		return false
	}
	j := slices.IndexFunc(after.Profiles, func(p model.StoredProfile) bool { return p.ID == runningID })
	return j < 0 || !sameJSON(after.Profiles[j].Outbounds, before.Profiles[i].Outbounds)
}

// sameJSON compares two JSON documents by their values, not their bytes.
func sameJSON(a, b json.RawMessage) bool {
	var x, y any
	if json.Unmarshal(a, &x) != nil || json.Unmarshal(b, &y) != nil {
		return string(a) == string(b)
	}
	return reflect.DeepEqual(x, y)
}

// matchExisting returns for each fresh server the saved one it replaces,
// or nil. Keys from strongest to weakest: the same link; the same endpoint
// (protocol, address, port, transport, TLS name, path); the same protocol,
// address and port. Each key is tried for every server before the next
// one, so a weak match never takes a server that a stronger key gives to
// another. Names are no key: panels put days left and traffic into them.
func matchExisting(old []model.StoredProfile, fresh []model.Key) []*model.StoredProfile {
	oldKeys := make([][3]string, len(old))
	for i, p := range old {
		oldKeys[i] = matchKeys(p.Link, p.Protocol, p.Address, p.Port, p.Network, p.Security, p.Outbounds)
	}
	freshKeys := make([][3]string, len(fresh))
	for i, k := range fresh {
		freshKeys[i] = matchKeys(k.Link, k.Protocol, k.Address, k.Port, k.Network, k.Security, k.Outbounds)
	}
	taken := make([]bool, len(old))
	result := make([]*model.StoredProfile, len(fresh))
	for level := range 3 {
		for i := range fresh {
			key := freshKeys[i][level]
			if result[i] != nil || key == "" {
				continue
			}
			for j := range old {
				if !taken[j] && oldKeys[j][level] == key {
					taken[j] = true
					result[i] = &old[j]
					break
				}
			}
		}
	}
	return result
}

func matchKeys(link, protocol, address string, port int, network, security string, outbounds json.RawMessage) [3]string {
	host := strings.ToLower(address)
	if strings.TrimSpace(link) == "" {
		link = ""
	}
	return [3]string{
		link,
		fmt.Sprintf("%s|%s|%d|%s|%s|%s", protocol, host, port, network, security, endpointDetails(outbounds)),
		fmt.Sprintf("%s|%s|%d", protocol, host, port),
	}
}

// transports are where Xray keeps a transport's settings, in the order the
// Android app reads them.
var transports = []string{"wsSettings", "httpupgradeSettings", "xhttpSettings", "splithttpSettings", "grpcSettings"}

// endpointDetails is the TLS or REALITY server name, the transport's path
// (or gRPC service) and its host header of the main outbound.
func endpointDetails(outbounds json.RawMessage) string {
	var list []struct {
		StreamSettings map[string]json.RawMessage `json:"streamSettings"`
	}
	if json.Unmarshal(outbounds, &list) != nil || len(list) == 0 || list[0].StreamSettings == nil {
		return ""
	}
	stream := list[0].StreamSettings
	tls := object(stream["realitySettings"])
	if tls == nil {
		tls = object(stream["tlsSettings"])
	}
	var transport map[string]json.RawMessage
	for _, name := range transports {
		if transport = object(stream[name]); transport != nil {
			break
		}
	}
	path := text(transport, "path")
	if path == "" {
		path = text(transport, "serviceName")
	}
	return strings.Join([]string{text(tls, "serverName"), path, text(transport, "host")}, "|")
}

// object reads a JSON object, or nil for anything else.
func object(raw json.RawMessage) map[string]json.RawMessage {
	var o map[string]json.RawMessage
	if json.Unmarshal(raw, &o) != nil {
		return nil
	}
	return o
}

// text reads a JSON string field; anything else counts as none.
func text(o map[string]json.RawMessage, key string) string {
	var s string
	if json.Unmarshal(o[key], &s) != nil {
		return ""
	}
	return s
}
