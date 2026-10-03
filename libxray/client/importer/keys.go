// Package importer turns keys and subscription bodies into servers ready
// to save, as the Android app's addLinks and pinWhereNeeded do.
package importer

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/linktext"
	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/libxray/internal/privileged"
)

const (
	// pinTimeoutMs bounds fetching one server's certificate, as on Android.
	pinTimeoutMs = 8000
	// maxPins bounds the certificate fetches of one import: each is a
	// connection to whatever the key names, outside the tunnel.
	maxPins = 16
)

// Check refuses a key by its outbounds; its error says why, in Russian.
type Check func(outbounds []json.RawMessage) error

// ForService is the Check of a privileged service (the Windows service
// runs the core as SYSTEM): it refuses anything beyond what share links
// make.
func ForService(outbounds []json.RawMessage) error { return privileged.Check(outbounds) }

// Keys turns text into servers ready to save, as the Android app's
// addLinks does: each link is parsed or, when there are none, the text is
// read as a pasted subscription body, and the result goes through Ready.
// skipped has a line in Russian for each key that cannot be used; err
// means the text holds no keys at all.
func Keys(ctx context.Context, text string, check Check) (keys []model.Key, skipped []string, err error) {
	var parsed []libxray.Profile
	if links := linktext.Links(text); len(links) > 0 {
		for _, link := range links {
			out, err := libxray.ParseLink(link)
			if err != nil {
				skipped = append(skipped, err.Error())
				continue
			}
			var p libxray.Profile
			if err := json.Unmarshal([]byte(out), &p); err != nil {
				return nil, nil, err
			}
			parsed = append(parsed, p)
		}
	} else {
		out, err := libxray.ParseSubscription([]byte(text))
		if err != nil {
			return nil, nil, err
		}
		var res libxray.SubscriptionResult
		if err := json.Unmarshal([]byte(out), &res); err != nil {
			return nil, nil, err
		}
		for _, p := range res.Profiles {
			parsed = append(parsed, *p)
		}
		skipped = append(skipped, res.Errors...)
	}
	keys, failed := Ready(ctx, parsed, check, nil, nil)
	return keys, append(skipped, failed...), nil
}

// Saved gives the outbounds a server was saved with, or nil.
type Saved func(libxray.Profile) json.RawMessage

// Ready makes servers ready to save of parsed profiles. A profile check
// refuses is left out (check may be nil). Those that ask to skip
// certificate checks get the server's certificate pinned, four at a time,
// at most maxPins, none once ctx ends; reuse may give the outbounds pinned
// for the very same link before, so an unchanged server is not contacted
// again, and when a certificate cannot be fetched, fallback may keep the
// server as it was saved (either may be nil). failed has a line in
// Russian for each profile that cannot be used.
func Ready(ctx context.Context, parsed []libxray.Profile, check Check, reuse, fallback Saved) (keys []model.Key, failed []string) {
	results := make([]struct {
		key  model.Key
		fail string
	}, len(parsed))
	limit := make(chan struct{}, 4)
	var wg sync.WaitGroup
	pins := 0
	for i, p := range parsed {
		if check != nil {
			if err := check(p.Outbounds); err != nil {
				results[i].fail = label(p) + ": " + err.Error()
				continue
			}
		}
		if !p.NeedsCertPin {
			results[i].key, results[i].fail = keyOf(p, nil)
			continue
		}
		if saved := call(reuse, p); saved != nil {
			results[i].key, results[i].fail = keyOf(p, saved)
			continue
		}
		if pins == maxPins {
			results[i].fail = label(p) + ": слишком много ключей без проверки сертификата за раз, добавьте его отдельно"
			continue
		}
		pins++
		wg.Go(func() {
			limit <- struct{}{}
			defer func() { <-limit }()
			if ctx.Err() != nil {
				results[i].fail = label(p) + ": добавление прервано"
				return
			}
			pinned, err := pin(p)
			switch {
			case err == nil:
				results[i].key, results[i].fail = keyOf(pinned, nil)
			case call(fallback, p) != nil:
				// The server may just be unreachable right now: kept as saved.
				results[i].key, results[i].fail = keyOf(p, call(fallback, p))
			default:
				results[i].fail = fmt.Sprintf("%s: не удалось получить сертификат сервера (%s)", label(p), err)
			}
		})
	}
	wg.Wait()
	for _, r := range results {
		if r.fail != "" {
			failed = append(failed, r.fail)
		} else {
			keys = append(keys, r.key)
		}
	}
	return keys, failed
}

func call(saved Saved, p libxray.Profile) json.RawMessage {
	if saved == nil {
		return nil
	}
	return saved(p)
}

// keyOf makes a server of p, with outbounds instead of its own when given;
// fail says why it cannot be used.
func keyOf(p libxray.Profile, outbounds json.RawMessage) (k model.Key, fail string) {
	if outbounds == nil {
		var err error
		if outbounds, err = json.Marshal(p.Outbounds); err != nil {
			return k, fmt.Sprintf("%s: %s", label(p), err)
		}
	}
	return model.Key{
		Name: p.Name, Protocol: p.Protocol, Address: p.Address, Port: p.Port,
		Network: p.Network, Security: p.Security, Link: p.Link, Outbounds: outbounds,
	}, ""
}

// label names p in a message, as its saved server will be named.
func label(p libxray.Profile) string { return model.Key{Name: p.Name, Address: p.Address}.Label() }

func pin(p libxray.Profile) (libxray.Profile, error) {
	hash, err := libxray.FetchCertSha256(p.Address, int32(p.Port), p.CertPinSNI, p.CertPinQuic, pinTimeoutMs)
	if err != nil {
		return p, err
	}
	in, err := json.Marshal(p)
	if err != nil {
		return p, err
	}
	out, err := libxray.PinCertificate(string(in), hash)
	if err != nil {
		return p, err
	}
	var pinned libxray.Profile
	return pinned, json.Unmarshal([]byte(out), &pinned)
}

// Summary is the message after an import: added servers were saved out
// of ready usable ones (the rest were saved before), skipped could not be
// used.
func Summary(added, ready int, skipped []string) string {
	switch {
	case added > 0 && len(skipped) == 0:
		return fmt.Sprintf("Добавлено серверов: %d", added)
	case added > 0:
		return fmt.Sprintf("Добавлено: %d, пропущено: %d (%s)", added, len(skipped), skipped[0])
	case ready > 0:
		return "Эти ключи уже добавлены"
	case len(skipped) > 0:
		return skipped[0]
	default:
		return "Не найдено ни одного ключа"
	}
}
