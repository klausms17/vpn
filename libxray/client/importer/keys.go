package importer

import (
	"encoding/json"
	"fmt"
	"sync"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/model"
)

// pinTimeoutMs bounds fetching one server's certificate, as on Android.
const pinTimeoutMs = 8000

// Keys turns text into servers ready to save, as the Android app's
// addLinks does: each link is parsed or, when there are none, the text is
// read as a pasted subscription body. Links that ask to skip certificate
// checks get the server's certificate pinned, four servers at a time.
// skipped has a line in Russian for each key that cannot be used; err
// means the text holds no keys at all.
func Keys(text string) (keys []model.Key, skipped []string, err error) {
	var parsed []libxray.Profile
	if links := Links(text); len(links) > 0 {
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

	results := make([]struct {
		key  model.Key
		fail string
	}, len(parsed))
	limit := make(chan struct{}, 4)
	var wg sync.WaitGroup
	for i, p := range parsed {
		wg.Go(func() {
			limit <- struct{}{}
			defer func() { <-limit }()
			results[i].key, results[i].fail = ready(p)
		})
	}
	wg.Wait()
	for _, r := range results {
		if r.fail != "" {
			skipped = append(skipped, r.fail)
		} else {
			keys = append(keys, r.key)
		}
	}
	return keys, skipped, nil
}

// ready pins the certificate of p if it needs one; fail says why p cannot
// be used.
func ready(p libxray.Profile) (k model.Key, fail string) {
	if p.NeedsCertPin {
		pinned, err := pin(p)
		if err != nil {
			return k, fmt.Sprintf("%s: не удалось получить сертификат сервера (%s)", p.Name, err)
		}
		p = pinned
	}
	outbounds, err := json.Marshal(p.Outbounds)
	if err != nil {
		return k, fmt.Sprintf("%s: %s", p.Name, err)
	}
	return model.Key{
		Name: p.Name, Protocol: p.Protocol, Address: p.Address, Port: p.Port,
		Network: p.Network, Security: p.Security, Link: p.Link, Outbounds: outbounds,
	}, ""
}

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
