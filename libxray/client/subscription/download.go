package subscription

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/importer"
	"github.com/klausms17/vpn/libxray/client/model"
)

// Fetch downloads a subscription with the device headers, directly or
// through the tunnel: the caller decides.
type Fetch func(ctx context.Context, url string) (*libxray.FetchResult, error)

// Direct downloads with userAgent and the device headers, without a core:
// on Windows the service's own sockets stay outside the tunnel.
func Direct(userAgent, headers string, timeoutMs int32) Fetch {
	return func(_ context.Context, url string) (*libxray.FetchResult, error) {
		return libxray.FetchWithHeaders(url, userAgent, headers, timeoutMs, "")
	}
}

// What the panel says instead of servers when its device limit refuses
// this device.
const (
	HWIDLimit        = "Достигнут лимит устройств для этой подписки"
	HWIDNotSupported = "Сервер подписки не принял это устройство. Сообщите владельцу подписки."
)

const (
	// maxPanelText bounds the panel's texts kept with a subscription.
	maxPanelText = 500
	// maxPanelURL bounds the addresses the panel gives.
	maxPanelURL = 1000
)

// Fetched is a downloaded subscription, ready to merge.
type Fetched struct {
	// Keys are its servers, ready to save; none means: keep the old ones.
	Keys []model.Key
	// Notice is what the panel said instead of, or besides, servers.
	Notice string
	// Errors has a line for each entry that could not be used.
	Errors []string
	// Dropped counts the entries past model.MaxProfiles, never made ready.
	Dropped int

	Title, UserInfo, SupportURL, Announce, ReportURL, AppURL string
}

// Download fetches url and makes its servers ready: check refuses some,
// and reuse and fallback are importer.Ready's (the pins of the
// subscription's saved servers). It fails when the download or its
// parsing fails, or when servers came and none can be used.
func Download(ctx context.Context, url string, fetch Fetch, check importer.Check, reuse, fallback importer.Saved) (Fetched, error) {
	r, err := fetch(ctx, url)
	if err != nil {
		return Fetched{}, bounded{err}
	}
	f := Fetched{
		Title:      decodeTitle(r.ProfileTitle),
		UserInfo:   clip(strings.TrimSpace(r.UserInfo), maxPanelText),
		SupportURL: clip(strings.TrimSpace(r.SupportUrl), maxPanelText),
		Announce:   clip(strings.TrimSpace(r.Announce), maxPanelText),
		ReportURL:  HTTPSURL(r.ReportUrl),
		AppURL:     HTTPSURL(r.AppUrl),
	}
	// Refused for the device limit: the body holds only placeholders, or
	// nothing.
	if r.HwidMaxDevices || r.HwidLimit || r.HwidNotSupported {
		f.Notice = HWIDLimit
		if r.HwidNotSupported {
			f.Notice = HWIDNotSupported
		}
		return f, nil
	}
	out, err := libxray.ParseSubscription(r.Body)
	if err != nil {
		return Fetched{}, bounded{err}
	}
	var res libxray.SubscriptionResult
	if err := json.Unmarshal([]byte(out), &res); err != nil {
		return Fetched{}, err
	}
	// No more than can be saved is made ready.
	if len(res.Profiles) > model.MaxProfiles {
		f.Dropped = len(res.Profiles) - model.MaxProfiles
		res.Profiles = res.Profiles[:model.MaxProfiles]
	}
	parsed := make([]libxray.Profile, 0, len(res.Profiles))
	for _, p := range res.Profiles {
		parsed = append(parsed, *p)
	}
	keys, failed := importer.Ready(ctx, parsed, check, reuse, fallback)
	f.Errors = append(res.Errors, failed...)
	// Servers came and none can be used: an error, not an empty list.
	if len(keys) == 0 && len(parsed) > 0 {
		if len(f.Errors) > 0 {
			return Fetched{}, errors.New(clip(f.Errors[0], maxPanelText))
		}
		return Fetched{}, errors.New("в подписке нет подходящих серверов")
	}
	f.Keys = keys
	if notice := strings.Join(res.Notices, "\n"); strings.TrimSpace(notice) != "" {
		f.Notice = clip(notice, maxPanelText)
	}
	return f, nil
}

// HTTPSURL returns text, trimmed, when it is a plain https URL with a host,
// else "": the panel's addresses are used only then (never http, no
// spaces, control characters or "user@" before the host).
func HTTPSURL(text string) string {
	t := strings.TrimSpace(text)
	if utf8.RuneCountInString(t) > maxPanelURL || len(t) < len("https://") || !strings.EqualFold(t[:len("https://")], "https://") {
		return ""
	}
	if strings.ContainsFunc(t, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) }) {
		return ""
	}
	rest := t[len("https://"):]
	authority := rest[:strings.IndexAny(rest+"/", "/?#")]
	host, _, _ := strings.Cut(authority, ":")
	if strings.HasPrefix(authority, "[") {
		host, _, _ = strings.Cut(authority[1:], "]")
	}
	if host == "" || strings.Contains(authority, "@") {
		return ""
	}
	return t
}

// decodeTitle reads a panel's "profile-title": plain, or "base64:…" when
// the core left it encoded.
func decodeTitle(raw string) string {
	t := strings.TrimSpace(raw)
	encoded, ok := strings.CutPrefix(t, "base64:")
	if !ok {
		return t
	}
	// Lenient, as Java's MIME decoder: whatever is not base64 is skipped.
	encoded = strings.Map(func(r rune) rune {
		if r == '+' || r == '/' || '0' <= r && r <= '9' || 'a' <= r && r <= 'z' || 'A' <= r && r <= 'Z' {
			return r
		}
		return -1
	}, encoded)
	data, err := base64.RawStdEncoding.DecodeString(encoded)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(strings.ToValidUTF8(string(data), "�"))
}

// clip cuts text to max characters.
func clip(text string, max int) string {
	if utf8.RuneCountInString(text) <= max {
		return text
	}
	return string([]rune(text)[:max])
}

// bounded cuts an error's text, which may carry what a panel sent (an
// HTTP status line may be megabytes long), to maxPanelText.
type bounded struct{ err error }

func (b bounded) Error() string { return clip(b.err.Error(), maxPanelText) }
func (b bounded) Unwrap() error { return b.err }
