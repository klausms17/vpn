package ui

import "testing"

func TestAnAddLinkWaitsForTheUser(t *testing.T) {
	var p pendingLink
	if _, ok := p.offer("--tray"); ok {
		t.Error("an argument taken for a link")
	}
	if _, ok := p.offer("klausvpn://settings/x"); ok || p.prompt().Present {
		t.Error("a link without a key or subscription was kept")
	}
	prompt, ok := p.offer("klausvpn://add/https://sub.example.com/abc#Ivan")
	if !ok || !prompt.Present || prompt.Host != "sub.example.com" || p.peek() != "https://sub.example.com/abc" {
		t.Errorf("prompt %+v, kept %q", prompt, p.peek())
	}
	// A key shows no host; the newest link wins.
	if prompt, _ := p.offer("KlausVPN://import/vless://uuid@h.example:443#x"); prompt.Host != "" || p.peek() != "vless://uuid@h.example:443#x" {
		t.Errorf("prompt %+v, kept %q", prompt, p.peek())
	}
	// Dropping an older one leaves the newer.
	p.drop("https://sub.example.com/abc")
	if p.peek() == "" {
		t.Error("the newer link was dropped")
	}
	p.drop(p.peek())
	if p.prompt().Present {
		t.Error("still waiting")
	}
}
