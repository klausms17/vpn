// Package privileged checks a server's outbounds before a privileged
// process runs them: the Windows service runs the core as SYSTEM, with
// keys that any user of the PC may add. An Xray outbound can do more than
// connect. xdrive's local storage creates and writes files, TLS and
// REALITY write key logs and read certificate files, a VLESS "reverse"
// lets the server into the PC's network, and finalmask's "udphop" or
// "xicmp" reach further than one connection. So only what share links
// make is let through: the protocols, transports and settings listed
// here. Keys are matched as Xray's JSON loader matches them, whatever
// their case; anything not listed is refused.
package privileged

import (
	"encoding/json"
	"fmt"
	"maps"
	"slices"
	"strings"
	"unicode/utf8"
)

// Check returns an error, in Russian, naming the first setting in
// outbounds that a privileged process must not run.
func Check(outbounds []json.RawMessage) error {
	if len(outbounds) == 0 {
		return fmt.Errorf("в ключе нет настроек сервера")
	}
	for i, raw := range outbounds {
		var v any
		if err := json.Unmarshal(raw, &v); err != nil {
			return fmt.Errorf("настройки сервера в ключе повреждены")
		}
		if err := checkOutbound(v, fmt.Sprintf("outbounds[%d]", i)); err != nil {
			return err
		}
	}
	return nil
}

// rule checks one value; path names it in the error.
type rule func(v any, path string) error

func refused(path string) error {
	return fmt.Errorf("ключ просит у ядра то, что Kirov VPN для Windows не выполняет (%s)", path)
}

// anything is for values Xray reads into types that cannot reach beyond
// the connection: names, numbers, headers, packet patterns.
func anything(any, string) error { return nil }

// oneOf takes a string equal, whatever its case, to one of values.
func oneOf(values ...string) rule {
	return func(v any, path string) error {
		if s, ok := v.(string); ok {
			for _, want := range values {
				if strings.EqualFold(s, want) {
					return nil
				}
			}
			path += ": " + clip(s)
		}
		return refused(path)
	}
}

// list takes an array whose every element passes each.
func list(each rule) rule {
	return func(v any, path string) error {
		items, ok := v.([]any)
		if !ok {
			return refused(path)
		}
		for i, item := range items {
			if err := each(item, fmt.Sprintf("%s[%d]", path, i)); err != nil {
				return err
			}
		}
		return nil
	}
}

// fields takes an object whose keys are all in allowed, each value passing
// its rule. A key that two spellings name, which Xray would read in an
// order of its own, is refused. Keys are checked in order, so the error
// names the same one every time.
func fields(allowed map[string]rule) rule {
	lower := make(map[string]rule, len(allowed))
	for k, r := range allowed {
		lower[strings.ToLower(k)] = r
	}
	return func(v any, path string) error {
		obj, ok := v.(map[string]any)
		if !ok {
			return refused(path)
		}
		seen := make(map[string]bool, len(obj))
		for _, k := range slices.Sorted(maps.Keys(obj)) {
			name := fold(k)
			r, ok := lower[name]
			if !ok || seen[name] {
				return refused(path + "." + clip(k))
			}
			seen[name] = true
			if err := r(obj[k], path+"."+k); err != nil {
				return err
			}
		}
		return nil
	}
}

// fold is a key as Xray matches it. Its names are plain ASCII; any other
// key is matched to none, as the folding of characters like the Kelvin
// sign could otherwise differ.
func fold(k string) string {
	for i := range len(k) {
		if k[i] >= utf8.RuneSelf {
			return ""
		}
	}
	return strings.ToLower(k)
}

func clip(s string) string {
	if utf8.RuneCountInString(s) > 40 {
		return string([]rune(s)[:39]) + "…"
	}
	return s
}

// The settings of each protocol share links make, without VLESS "reverse"
// and without "freedom", which would send everything past the server.
var settings = map[string]rule{
	"vless": fields(map[string]rule{
		"vnext": list(fields(map[string]rule{
			"address": anything, "port": anything,
			"users": list(fields(map[string]rule{
				"id": anything, "encryption": anything, "flow": anything, "level": anything, "email": anything,
			})),
		})),
		"address": anything, "port": anything, "id": anything, "encryption": anything,
		"flow": anything, "level": anything, "email": anything,
	}),
	"vmess": fields(map[string]rule{
		"vnext": list(fields(map[string]rule{
			"address": anything, "port": anything,
			"users": list(fields(map[string]rule{
				"id": anything, "security": anything, "alterId": anything, "level": anything, "email": anything,
			})),
		})),
	}),
	"trojan": fields(map[string]rule{
		"servers": list(fields(map[string]rule{
			"address": anything, "port": anything, "password": anything, "level": anything, "email": anything,
		})),
	}),
	"shadowsocks": fields(map[string]rule{
		"servers": list(fields(map[string]rule{
			"address": anything, "port": anything, "method": anything, "password": anything,
			"level": anything, "email": anything, "uot": anything, "uotVersion": anything,
		})),
	}),
	"hysteria": fields(map[string]rule{"version": anything, "address": anything, "port": anything}),
}

func checkOutbound(v any, path string) error {
	obj, ok := v.(map[string]any)
	if !ok {
		return refused(path)
	}
	protocol := ""
	for k, val := range obj {
		if fold(k) == "protocol" {
			protocol, _ = val.(string)
		}
	}
	s, ok := settings[strings.ToLower(protocol)]
	if !ok {
		return refused(path + ".protocol: " + clip(protocol))
	}
	return fields(map[string]rule{
		"tag": anything, "protocol": anything, "settings": s, "streamSettings": streamSettings, "mux": mux,
	})(v, path)
}

var (
	streamSettings = stream(false)
	mux            = fields(map[string]rule{
		"enabled": anything, "concurrency": anything, "xudpConcurrency": anything, "xudpProxyUDP443": anything,
	})
	tlsSettings = fields(map[string]rule{
		"serverName": anything, "alpn": anything, "fingerprint": anything,
		"pinnedPeerCertSha256": anything, "verifyPeerCertByName": anything,
		"cipherSuites": anything, "curvePreferences": anything, "echConfigList": anything,
		"minVersion": anything, "maxVersion": anything, "enableSessionResumption": anything,
	})
	realitySettings = fields(map[string]rule{
		"serverName": anything, "fingerprint": anything, "password": anything, "publicKey": anything,
		"shortId": anything, "spiderX": anything, "mldsa65Verify": anything,
	})
	rawSettings  = fields(map[string]rule{"header": anything})
	httpSettings = fields(map[string]rule{"path": anything, "host": anything, "headers": anything})
	wsSettings   = fields(map[string]rule{"path": anything, "host": anything, "headers": anything, "heartbeatPeriod": anything})
	grpcSettings = fields(map[string]rule{
		"serviceName": anything, "authority": anything, "multiMode": anything, "idle_timeout": anything,
		"health_check_timeout": anything, "permit_without_stream": anything,
		"initial_windows_size": anything, "user_agent": anything,
	})
	hysteriaSettings = fields(map[string]rule{"version": anything, "auth": anything, "udpIdleTimeout": anything})
	mask             = func(types ...string) rule {
		return fields(map[string]rule{"type": oneOf(types...), "settings": anything})
	}
	finalmask = fields(map[string]rule{
		"tcp": list(mask("fragment", "header-custom")),
		"udp": list(mask("salamander", "noise", "header-custom")),
		"quicParams": fields(map[string]rule{
			"congestion": anything, "bbrProfile": anything, "brutalUp": anything, "brutalDown": anything,
			"brutalDisableLossCompensation": anything, "initStreamReceiveWindow": anything,
			"maxStreamReceiveWindow": anything, "initConnectionReceiveWindow": anything,
			"maxConnectionReceiveWindow": anything, "maxIdleTimeout": anything, "keepAlivePeriod": anything,
			"disablePathMTUDiscovery": anything, "disableChromeParrot": anything, "disableGSO": anything,
			"maxIncomingStreams": anything, "disableStatelessReset": anything,
		}),
	})
	sockopt = fields(map[string]rule{
		"domainStrategy": anything, "tcpFastOpen": anything, "tcpKeepAliveInterval": anything,
		"tcpKeepAliveIdle": anything, "tcpCongestion": anything, "tcpMptcp": anything,
		"tcpUserTimeout": anything, "tcpWindowClamp": anything, "tcpMaxSeg": anything,
	})
)

// stream is streamSettings; nested, it is an XHTTP downloadSettings, which
// also names its own server and may not nest another.
func stream(nested bool) rule {
	xhttp := xhttpSettings(nested)
	allowed := map[string]rule{
		"network":  oneOf("raw", "tcp", "xhttp", "splithttp", "grpc", "ws", "websocket", "httpupgrade", "hysteria"),
		"security": oneOf("", "none", "tls", "reality"),

		"tlsSettings": tlsSettings, "realitySettings": realitySettings,
		"rawSettings": rawSettings, "tcpSettings": rawSettings,
		"wsSettings": wsSettings, "httpupgradeSettings": httpSettings, "grpcSettings": grpcSettings,
		"xhttpSettings": xhttp, "splithttpSettings": xhttp,
		"hysteriaSettings": hysteriaSettings,
		"finalmask":        finalmask,
		"sockopt":          sockopt,
	}
	if nested {
		allowed["address"] = anything
		allowed["port"] = anything
	}
	return fields(allowed)
}

// xhttpSettings is XHTTP's settings. Xray replaces them with "extra" when
// it is there, so extra takes the same, but no further extra.
func xhttpSettings(nested bool) rule {
	plain := map[string]rule{
		"host": anything, "path": anything, "mode": anything, "headers": anything,
		"xPaddingBytes": anything, "xPaddingObfsMode": anything, "xPaddingKey": anything,
		"xPaddingHeader": anything, "xPaddingPlacement": anything, "xPaddingMethod": anything,
		"uplinkHTTPMethod": anything, "sessionIDPlacement": anything, "sessionIDKey": anything,
		"sessionIDTable": anything, "sessionIDLength": anything, "seqPlacement": anything,
		"seqKey": anything, "uplinkDataPlacement": anything, "uplinkDataKey": anything,
		"uplinkChunkSize": anything, "noGRPCHeader": anything, "noSSEHeader": anything,
		"scMaxEachPostBytes": anything, "scMinPostsIntervalMs": anything,
		"scMaxBufferedPosts": anything, "scStreamUpServerSecs": anything,
		"xmux": fields(map[string]rule{
			"maxConcurrency": anything, "maxConnections": anything, "cMaxReuseTimes": anything,
			"hMaxRequestTimes": anything, "hMaxReusableSecs": anything, "hKeepAlivePeriod": anything,
		}),
	}
	if nested {
		return fields(plain)
	}
	plain["downloadSettings"] = stream(true)
	extra := fields(plain)
	withExtra := make(map[string]rule, len(plain)+1)
	for k, r := range plain {
		withExtra[k] = r
	}
	withExtra["extra"] = extra
	return fields(withExtra)
}
