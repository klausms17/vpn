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
	first, ok := p.offer("klausvpn://add/https://sub.example.com/abc#Ivan")
	if !ok || !first.Present || first.Host != "sub.example.com" || p.peek(first.Seq) != "https://sub.example.com/abc" {
		t.Errorf("prompt %+v, kept %q", first, p.peek(first.Seq))
	}
	// A key shows no host; the newest link wins, and the answer about the
	// first one neither adds nor drops it.
	second, _ := p.offer("KlausVPN://import/vless://uuid@h.example:443#x")
	if second.Host != "" || second.Seq == first.Seq || p.peek(second.Seq) != "vless://uuid@h.example:443#x" {
		t.Errorf("prompt %+v, kept %q", second, p.peek(second.Seq))
	}
	if p.peek(first.Seq) != "" {
		t.Error("the answer about the first link would add the second")
	}
	p.drop(first.Seq)
	if !p.prompt().Present {
		t.Error("the newer link was dropped")
	}
	p.drop(second.Seq)
	if p.prompt().Present {
		t.Error("still waiting")
	}
}
