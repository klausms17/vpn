package service

import (
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// torrentClients are the programs Settings.TorrentsDirect sends directly:
// downloads stay fast, and the VPN's server gets no complaints about what
// they share. Xray compares names exactly, so each is spelt as its program
// ships it, and torrentRules adds it in lower case too.
var torrentClients = []string{
	"qbittorrent.exe", "uTorrent.exe", "utweb.exe", "BitTorrent.exe", "btweb.exe",
	"transmission-qt.exe", "transmission-daemon.exe", "deluge.exe", "deluged.exe",
	"BitComet.exe", "tixati.exe", "BiglyBT.exe", "Azureus.exe", "PicoTorrent.exe", "MediaGet.exe",
}

// torrentRules are torrentClients as they ship and in lower case.
var torrentRules = func() []string {
	out := slices.Clone(torrentClients)
	for _, c := range torrentClients {
		if l := strings.ToLower(c); !slices.Contains(out, l) {
			out = append(out, l)
		}
	}
	return out
}()

func defaultSettings() ipc.Settings {
	return ipc.Settings{Mode: ipc.ModeRuDirect, TorrentsDirect: true, AutoConnect: true}
}

// Limits of what a window may save, so that the settings stay small and
// the core quick to start: Xray compiles every regexp at each start and
// tries them one by one on each new connection.
const (
	maxSites         = 1000
	maxPrograms      = 200
	maxRegexps       = 50
	maxSettingsBytes = 256 << 10
)

// cleanSettings checks settings from a window and returns them as they are
// to be saved, or an error in Russian. site turns a site the user typed
// into the form it is saved in, "" if it is not one (libxray's
// UserRuleEntry).
func cleanSettings(s ipc.Settings, site func(string) string) (ipc.Settings, error) {
	switch s.Mode {
	case ipc.ModeRuDirect, ipc.ModeBlockedOnly, ipc.ModeGlobal:
	default:
		return s, errBadRequest
	}
	var err error
	for _, list := range []*[]string{&s.DirectSites, &s.ProxySites, &s.BlockSites} {
		if *list, err = cleanSites(*list, site); err != nil {
			return s, err
		}
	}
	for _, list := range []*[]string{&s.DirectPrograms, &s.ProxyPrograms} {
		if *list, err = cleanPrograms(*list); err != nil {
			return s, err
		}
	}
	regexps := 0
	for _, list := range [][]string{s.DirectSites, s.ProxySites, s.BlockSites} {
		for _, e := range list {
			if strings.HasPrefix(e, "regexp:") {
				regexps++
			}
		}
	}
	if regexps > maxRegexps {
		return s, fmt.Errorf("Слишком много правил regexp: не больше %d", maxRegexps)
	}
	// As the windows get them: a bigger event would never reach them.
	if b, err := json.Marshal(ipc.NewEvent(ipc.EventSettings, s)); err != nil || len(b) > maxSettingsBytes {
		return s, errors.New("Слишком много правил: удалите часть")
	}
	return s, nil
}

func cleanSites(in []string, site func(string) string) ([]string, error) {
	if len(in) > maxSites {
		return nil, fmt.Errorf("Слишком много сайтов в одном списке: не больше %d", maxSites)
	}
	out := []string{}
	for _, e := range in {
		c := site(e)
		if c == "" {
			return nil, fmt.Errorf("«%s» не похоже на сайт или адрес", clip(e))
		}
		if !slices.Contains(out, c) {
			out = append(out, c)
		}
	}
	return out, nil
}

// cleanPrograms takes file names of programs ("Telegram.exe"), each once.
// Xray compares them exactly, so their case stays as Windows has it.
func cleanPrograms(in []string) ([]string, error) {
	if len(in) > maxPrograms {
		return nil, fmt.Errorf("Слишком много программ в одном списке: не больше %d", maxPrograms)
	}
	out := []string{}
	for _, p := range in {
		p = strings.TrimSpace(p)
		if len(p) <= 4 || !strings.EqualFold(p[len(p)-4:], ".exe") || libxray.ProgramName(p) == "" {
			return nil, fmt.Errorf("«%s» не похоже на программу: нужен файл .exe", clip(p))
		}
		if !slices.Contains(out, p) {
			out = append(out, p)
		}
	}
	return out, nil
}

// directPrograms are the programs that go directly under s.
func directPrograms(s ipc.Settings) []string {
	if !s.TorrentsDirect {
		return s.DirectPrograms
	}
	return append(slices.Clone(s.DirectPrograms), torrentRules...)
}

// clip shortens what the user typed for a message.
func clip(s string) string {
	if utf8.RuneCountInString(s) > 60 {
		return string([]rune(s)[:59]) + "…"
	}
	return s
}
