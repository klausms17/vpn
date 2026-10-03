package model

import "slices"

// The changes below are pure: the store runs them on the state it read,
// under its lock, so nothing saved meanwhile is lost (Android's
// ProfilesOps.kt).

// WithSelected selects server id, unless it is gone (the list was replaced
// meanwhile).
func (s ProfilesState) WithSelected(id string) ProfilesState {
	if s.SelectedID != id && slices.ContainsFunc(s.Profiles, func(p StoredProfile) bool { return p.ID == id }) {
		s.SelectedID = id
	}
	return s
}

// WithAdded appends added. The selection stays if it still names a
// server, else the first added server is selected.
func (s ProfilesState) WithAdded(added []StoredProfile) ProfilesState {
	if len(added) == 0 {
		return s
	}
	_, ok := s.Selected()
	s.Profiles = append(slices.Clone(s.Profiles), added...)
	if !ok {
		s.SelectedID = added[0].ID
	}
	return s
}

// FreshKeys returns the keys of ready whose links are not saved yet, each
// once (a message may repeat a key). Keys without a link (a pasted
// subscription body) are told apart by their outbounds.
func FreshKeys(ready []Key, saved map[string]bool) []Key {
	var fresh []Key
	seen := map[string]bool{}
	for _, k := range ready {
		if k.Link != "" && saved[k.Link] {
			continue
		}
		id := k.Link
		if id == "" {
			id = string(k.Outbounds)
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		fresh = append(fresh, k)
	}
	return fresh
}

// MaxProfiles bounds the saved servers, so that their list always fits in
// one message to the window.
const MaxProfiles = 1000

// MaxSubscriptions bounds the saved subscriptions, for the same reason and
// because each is downloaded every hour.
const MaxSubscriptions = 20

// WithNewKeys appends the keys of ready that are not saved yet as own
// keys, as many as fit under MaxProfiles; it returns the new state, the
// servers actually added and how many new keys did not fit. newID and now
// name and date each of them.
func (s ProfilesState) WithNewKeys(ready []Key, newID func() string, now int64) (next ProfilesState, added []StoredProfile, left int) {
	saved := map[string]bool{}
	for _, p := range s.Profiles {
		if p.Link != "" {
			saved[p.Link] = true
		}
	}
	fresh := FreshKeys(ready, saved)
	room := max(MaxProfiles-len(s.Profiles), 0)
	if len(fresh) > room {
		fresh, left = fresh[:room], len(fresh)-room
	}
	for _, k := range fresh {
		added = append(added, k.Stored(newID(), "", now))
	}
	return s.WithAdded(added), added, left
}

// Renamed names server id name.
func (s ProfilesState) Renamed(id, name string) ProfilesState {
	s.Profiles = slices.Clone(s.Profiles)
	for i := range s.Profiles {
		if s.Profiles[i].ID == id {
			s.Profiles[i].Name = ClipName(name)
		}
	}
	return s
}

// WithoutProfile removes server id; if it was selected, the first
// remaining server is selected.
func (s ProfilesState) WithoutProfile(id string) ProfilesState {
	s.Profiles = slices.DeleteFunc(slices.Clone(s.Profiles), func(p StoredProfile) bool { return p.ID == id })
	if s.SelectedID == id {
		s.SelectedID = firstID(s.Profiles)
	}
	return s
}

// WithoutSubscription removes subscription id with all its servers. A
// selection outside it stays, otherwise the first remaining server is
// selected.
func (s ProfilesState) WithoutSubscription(id string) ProfilesState {
	s.Profiles = slices.DeleteFunc(slices.Clone(s.Profiles), func(p StoredProfile) bool { return p.SubscriptionID == id })
	s.Subscriptions = slices.DeleteFunc(slices.Clone(s.Subscriptions), func(sub Subscription) bool { return sub.ID == id })
	if _, ok := s.Selected(); !ok {
		s.SelectedID = firstID(s.Profiles)
	}
	return s
}

func firstID(profiles []StoredProfile) string {
	if len(profiles) == 0 {
		return ""
	}
	return profiles[0].ID
}
