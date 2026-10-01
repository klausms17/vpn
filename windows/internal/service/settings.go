package service

import (
	"fmt"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/klausms17/vpn/windows/internal/ipc"
)

// torrentClients are the programs Settings.TorrentsDirect sends directly:
// downloads stay fast, and the VPN's server gets no complaints about what
// they share. Xray compares names as they are, so each is spelt as its
// program ships it.
var torrentClients = []string{
	"qbittorrent.exe", "uTorrent.exe", "utweb.exe", "BitTorrent.exe", "btweb.exe",
	"transmission-qt.exe", "transmission-daemon.exe", "deluge.exe", "deluged.exe",
	"BitComet.exe", "tixati.exe", "BiglyBT.exe", "Azureus.exe", "PicoTorrent.exe", "MediaGet.exe",
}

func defaultSettings() ipc.Settings {
	return ipc.Settings{Mode: ipc.ModeRuDirect, TorrentsDirect: true, AutoConnect: true}
}

// Limits of what a window may save, so that the settings stay small.
const (
	maxSites    = 1000
	maxPrograms = 200
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
func cleanPrograms(in []string) ([]string, error) {
	if len(in) > maxPrograms {
		return nil, fmt.Errorf("Слишком много программ в одном списке: не больше %d", maxPrograms)
	}
	out := []string{}
	for _, p := range in {
		p = strings.TrimSpace(p)
		if len(p) <= 4 || len(p) > 255 || !strings.EqualFold(p[len(p)-4:], ".exe") || strings.ContainsAny(p, `/\:*?"<>|`) {
			return nil, fmt.Errorf("«%s» не похоже на программу: нужен файл .exe", clip(p))
		}
		if !slices.ContainsFunc(out, func(o string) bool { return strings.EqualFold(o, p) }) {
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
	return append(slices.Clone(s.DirectPrograms), torrentClients...)
}

// clip shortens what the user typed for a message.
func clip(s string) string {
	if utf8.RuneCountInString(s) > 60 {
		return string([]rune(s)[:59]) + "…"
	}
	return s
}
