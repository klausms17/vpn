package libxray

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"regexp"
	"strings"
)

// Routing modes.
const (
	// ModeRuDirect sends Russian sites and IPs directly and everything else
	// through the proxy. The default.
	ModeRuDirect = "ru_direct"
	// ModeBlockedOnly sends only sites blocked in Russia through the proxy.
	ModeBlockedOnly = "blocked_only"
	// ModeGlobal sends everything (except LAN) through the proxy.
	ModeGlobal = "global"
)

// Categories the app keeps in its trimmed geo files. Anything referenced by
// a generated config must be in these lists (see TrimGeoFile).
const (
	GeositeCodes = "category-ru,tld-ru,ru-blocked,ru-available-only-inside,category-gov-ru,private"
	GeoipCodes   = "ru,private,ru-blocked,ru-blocked-community,ru-whitelist,telegram,facebook,twitter"
)

const (
	tunInboundTag   = "tun-in"
	socksInboundTag = "socks-in"
	dnsModuleTag    = "dns-module"
	blockTag        = "block"
	dnsOutTag       = "dns-out"
)

var (
	ruDNS  = []string{"77.88.8.8", "77.88.8.1"} // Yandex DNS
	dohDNS = []string{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"}

	// Russian sites that must not go through a foreign exit.
	ruDirectDomains = []string{
		"geosite:private", "geosite:category-ru", "geosite:tld-ru",
		"geosite:ru-available-only-inside", "geosite:category-gov-ru",
	}
)

// BuildOptions is the input of BuildConfig (passed as JSON from the app).
type BuildOptions struct {
	// Outbounds of the selected profile; [0] is the proxy.
	Outbounds []json.RawMessage `json:"outbounds"`
	Mode      string            `json:"mode"`
	IPv6      bool              `json:"ipv6"`
	// User rules: domains ("example.com", "full:x", "keyword:x",
	// "regexp:x") or IPs/CIDRs, one per entry.
	DirectRules []string `json:"directRules"`
	ProxyRules  []string `json:"proxyRules"`
	BlockRules  []string `json:"blockRules"`

	LogLevel string `json:"logLevel"` // none|error|warning|info|debug
	LogFile  string `json:"logFile"`

	// Tun adds the TUN inbound fed by the Android VpnService fd.
	Tun    bool `json:"tun"`
	TunMTU int  `json:"tunMtu"`
	// SocksPort adds a 127.0.0.1 SOCKS inbound. Only for tests: the app
	// never opens local ports, because any app on the phone could use them
	// to detect the VPN or reach the proxy.
	SocksPort int `json:"socksPort"`
}

// BuildConfig turns BuildOptions (JSON) into a complete Xray config (JSON).
func BuildConfig(optionsJSON string) (string, error) {
	var o BuildOptions
	if err := json.Unmarshal([]byte(optionsJSON), &o); err != nil {
		return "", fmt.Errorf("bad options: %w", err)
	}
	cfg, err := buildConfig(&o)
	if err != nil {
		return "", err
	}
	out, err := json.MarshalIndent(cfg, "", "  ")
	return string(out), err
}

// BuildProxyOnlyConfig makes the minimal config used for delay tests and
// downloads through a profile: just the profile outbounds, no routing.
func BuildProxyOnlyConfig(outboundsJSON string) (string, error) {
	var outbounds []json.RawMessage
	if err := json.Unmarshal([]byte(outboundsJSON), &outbounds); err != nil {
		return "", fmt.Errorf("bad outbounds: %w", err)
	}
	obs, err := prepareOutbounds(outbounds)
	if err != nil {
		return "", err
	}
	cfg := map[string]any{
		"log":       map[string]any{"loglevel": "none"},
		"outbounds": obs,
	}
	out, err := json.Marshal(cfg)
	return string(out), err
}

func prepareOutbounds(outbounds []json.RawMessage) ([]any, error) {
	if len(outbounds) == 0 {
		return nil, errors.New("profile has no outbounds")
	}
	res := make([]any, 0, len(outbounds))
	for i, raw := range outbounds {
		var ob map[string]any
		if err := json.Unmarshal(raw, &ob); err != nil {
			return nil, fmt.Errorf("outbound %d: %w", i, err)
		}
		sanitizeOutbound(ob)
		tag, _ := ob["tag"].(string)
		if i == 0 {
			ob["tag"] = ProxyTag
		} else {
			switch tag {
			case "", ProxyTag, DirectTag, blockTag, dnsOutTag:
				return nil, fmt.Errorf("outbound %d uses reserved tag %q", i, tag)
			}
		}
		res = append(res, ob)
	}
	return res, nil
}

// namespaceOutbounds prepares a profile's outbounds like
// BuildProxyOnlyConfig and moves them under prefix, so that several
// profiles fit into one instance: every tag becomes prefix+"-"+tag (the
// root prefix+"-proxy") and dialerProxy references follow. It returns the
// outbounds and the root's tag.
func namespaceOutbounds(outbounds []json.RawMessage, prefix string) ([]any, string, error) {
	obs, err := prepareOutbounds(outbounds)
	if err != nil {
		return nil, "", err
	}
	rename := map[string]string{}
	for _, x := range obs {
		tag, _ := x.(map[string]any)["tag"].(string)
		if _, dup := rename[tag]; dup {
			return nil, "", fmt.Errorf("duplicate outbound tag %q", tag)
		}
		rename[tag] = prefix + "-" + tag
	}
	for _, x := range obs {
		ob := x.(map[string]any)
		ob["tag"] = rename[ob["tag"].(string)]
		if ss, ok := ob["streamSettings"].(map[string]any); ok {
			if so, ok := ss["sockopt"].(map[string]any); ok {
				if t, _ := so["dialerProxy"].(string); t != "" {
					// A hop outside the profile would be another
					// candidate's outbound (or none at all).
					if rename[t] == "" {
						return nil, "", fmt.Errorf("dialerProxy %q is not in the profile", t)
					}
					so["dialerProxy"] = rename[t]
				}
			}
		}
	}
	return obs, rename[ProxyTag], nil
}

type rule = map[string]any

func buildConfig(o *BuildOptions) (map[string]any, error) {
	switch o.Mode {
	case "":
		o.Mode = ModeRuDirect
	case ModeRuDirect, ModeBlockedOnly, ModeGlobal:
	default:
		return nil, fmt.Errorf("unknown mode %q", o.Mode)
	}
	outbounds, err := prepareOutbounds(o.Outbounds)
	if err != nil {
		return nil, err
	}
	outbounds = append(outbounds,
		map[string]any{"tag": DirectTag, "protocol": "freedom"},
		map[string]any{"tag": blockTag, "protocol": "blackhole"},
		// Level 1: DNS flows are freed after seconds, not the default minutes
		// (each app query is its own flow).
		map[string]any{"tag": dnsOutTag, "protocol": "dns", "settings": map[string]any{"userLevel": 1}},
	)

	sniffing := map[string]any{
		"enabled":      true,
		"destOverride": []string{"http", "tls", "quic"},
		// Route by the sniffed domain but keep connecting to the IP the app
		// resolved through our DNS: never breaks apps that pin IPs.
		"routeOnly": true,
	}
	var inbounds []any
	var dnsInbounds []string
	if o.Tun {
		mtu := o.TunMTU
		if mtu <= 0 {
			mtu = TunMTU
		}
		inbounds = append(inbounds, map[string]any{
			"tag":      tunInboundTag,
			"protocol": "tun",
			"port":     0,
			// An explicit name avoids interface enumeration, which Android
			// forbids for apps.
			"settings": map[string]any{"name": "tun0", "mtu": mtu},
			"sniffing": sniffing,
		})
		dnsInbounds = append(dnsInbounds, tunInboundTag)
	}
	if o.SocksPort > 0 {
		inbounds = append(inbounds, map[string]any{
			"tag":      socksInboundTag,
			"protocol": "socks",
			"listen":   "127.0.0.1",
			"port":     o.SocksPort,
			"settings": map[string]any{"udp": true, "auth": "noauth"},
			"sniffing": sniffing,
		})
		dnsInbounds = append(dnsInbounds, socksInboundTag)
	}
	if len(inbounds) == 0 {
		return nil, errors.New("config needs a tun or socks inbound")
	}

	var rules []rule
	// 1. DNS from apps is answered by Xray's DNS module (see buildDNS).
	rules = append(rules, rule{"inboundTag": dnsInbounds, "port": "53", "outboundTag": dnsOutTag})
	if o.Tun {
		// Anything else sent to the tunnel's own addresses is refused at
		// once. Android's "Private DNS (automatic)" probes DNS-over-TLS on
		// port 853 of the VPN DNS address; refusing it immediately makes
		// Android fall back to plain DNS without a multi-second stall.
		rules = append(rules, rule{"ip": []string{TunDNSv4 + "/32", TunIPv4 + "/32"}, "outboundTag": blockTag})
	}
	// 2. The DNS module's own upstream queries.
	if o.Mode != ModeGlobal {
		rules = append(rules, rule{"inboundTag": []string{dnsModuleTag}, "ip": ruDNS, "outboundTag": DirectTag})
	}
	rules = append(rules, rule{"inboundTag": []string{dnsModuleTag}, "outboundTag": ProxyTag})
	// 3. Without IPv6 an IPv6 connection must fail fast instead of leaking
	// or hanging; apps then fall back to IPv4.
	if !o.IPv6 {
		rules = append(rules, rule{"ip": []string{"::/0"}, "outboundTag": blockTag})
	}
	// 4. User rules.
	for _, ur := range []struct {
		entries []string
		tag     string
	}{{o.BlockRules, blockTag}, {o.DirectRules, DirectTag}, {o.ProxyRules, ProxyTag}} {
		domains, ips := splitUserRules(ur.entries)
		if len(domains) > 0 {
			rules = append(rules, rule{"domain": domains, "outboundTag": ur.tag})
		}
		if len(ips) > 0 {
			rules = append(rules, rule{"ip": ips, "outboundTag": ur.tag})
		}
	}
	// 5. The mode itself.
	switch o.Mode {
	case ModeRuDirect:
		rules = append(rules,
			rule{"domain": []string{"geosite:ru-blocked"}, "outboundTag": ProxyTag},
			rule{"domain": ruDirectDomains, "outboundTag": DirectTag},
			rule{"ip": []string{"geoip:private", "geoip:ru"}, "outboundTag": DirectTag},
			rule{"network": "tcp,udp", "outboundTag": ProxyTag},
		)
	case ModeBlockedOnly:
		rules = append(rules,
			rule{"domain": []string{"geosite:ru-blocked"}, "outboundTag": ProxyTag},
			rule{"ip": []string{"geoip:ru-blocked", "geoip:ru-blocked-community", "geoip:telegram", "geoip:facebook", "geoip:twitter"}, "outboundTag": ProxyTag},
			rule{"network": "tcp,udp", "outboundTag": DirectTag},
		)
	case ModeGlobal:
		rules = append(rules,
			rule{"ip": []string{"geoip:private"}, "outboundTag": DirectTag},
			rule{"network": "tcp,udp", "outboundTag": ProxyTag},
		)
	}

	logLevel := o.LogLevel
	if logLevel == "" {
		logLevel = "warning"
	}
	logCfg := map[string]any{"loglevel": logLevel, "access": "none", "dnsLog": false}
	if o.LogFile != "" {
		logCfg["error"] = o.LogFile
	}

	return map[string]any{
		"log":   logCfg,
		"stats": map[string]any{},
		"policy": map[string]any{
			"levels": map[string]any{"1": map[string]any{"connIdle": 10}},
			"system": map[string]any{"statsOutboundUplink": true, "statsOutboundDownlink": true},
		},
		"dns":       buildDNS(o),
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"routing":   map[string]any{"domainStrategy": "AsIs", "rules": rules},
	}, nil
}

func buildDNS(o *BuildOptions) map[string]any {
	var servers []any
	doh := func(domains []string) {
		for i, addr := range dohDNS {
			s := map[string]any{"address": addr}
			if domains != nil {
				s["domains"] = domains
				s["skipFallback"] = true
				if i == len(dohDNS)-1 {
					// Blocked domains must never fall through to Yandex or
					// the ISP resolver when encrypted DNS is slow.
					s["finalQuery"] = true
				}
			}
			servers = append(servers, s)
		}
	}
	ru := func(domains []string) {
		for _, addr := range append(append([]string{}, ruDNS...), "localhost") {
			s := map[string]any{"address": addr}
			if addr != "localhost" {
				// Yandex answers in tens of ms; where outside DNS is blocked
				// (offices, hotels) do not stall every lookup for seconds.
				s["timeoutMs"] = 1000
			}
			if domains != nil {
				s["domains"] = domains
				s["skipFallback"] = true
			}
			servers = append(servers, s)
		}
	}
	// The router answers its own admin names (tplinkwifi.net, my.keenetic.net).
	servers = append(servers, map[string]any{
		"address":      "localhost",
		"domains":      []string{"geosite:private", "full:my.keenetic.net", "domain:routerlogin.net"},
		"skipFallback": true,
	})
	switch o.Mode {
	case ModeRuDirect:
		// Blocked .ru domains must not be resolved by a Russian resolver.
		doh([]string{"geosite:ru-blocked"})
		ru(ruDirectDomains)
		doh(nil)
	case ModeBlockedOnly:
		doh([]string{"geosite:ru-blocked"})
		ru(nil)
	case ModeGlobal:
		doh(nil)
	}
	strategy := "UseIPv4"
	if o.IPv6 {
		strategy = "UseIP"
	}
	return map[string]any{
		"tag":                    dnsModuleTag,
		"queryStrategy":          strategy,
		"disableFallbackIfMatch": true,
		"servers":                servers,
	}
}

var domainRe = regexp.MustCompile(`^[a-z0-9\p{L}_]([a-z0-9\p{L}_-]*[a-z0-9\p{L}_])?(\.[a-z0-9\p{L}_]([a-z0-9\p{L}_-]*[a-z0-9\p{L}_])?)*\.?$`)

// splitUserRules converts user entries into Xray domain and IP matchers,
// silently dropping anything invalid so a typo can never stop the VPN.
func splitUserRules(entries []string) (domains, ips []string) {
	for _, e := range entries {
		e = strings.TrimSpace(strings.ToLower(e))
		if e == "" || strings.HasPrefix(e, "#") {
			continue
		}
		if prefix, rest, ok := strings.Cut(e, ":"); ok {
			switch prefix {
			case "domain", "full", "keyword":
				if rest != "" && (prefix == "keyword" || domainRe.MatchString(rest)) {
					domains = append(domains, prefix+":"+rest)
				}
				continue
			case "regexp":
				if _, err := regexp.Compile(rest); err == nil && rest != "" {
					domains = append(domains, e)
				}
				continue
			case "http", "https":
				// A pasted URL: keep the host.
				if h := hostFromURL(e); h != "" {
					domains = append(domains, "domain:"+h)
				}
				continue
			}
		}
		if pfx, err := netip.ParsePrefix(e); err == nil {
			ips = append(ips, pfx.Masked().String())
			continue
		}
		if addr, err := netip.ParseAddr(e); err == nil {
			ips = append(ips, addr.String())
			continue
		}
		e = strings.TrimPrefix(e, "*.")
		e = strings.TrimPrefix(e, ".")
		if domainRe.MatchString(e) {
			domains = append(domains, "domain:"+strings.TrimSuffix(e, "."))
		}
	}
	return domains, ips
}

func hostFromURL(s string) string {
	_, rest, _ := strings.Cut(s, "://")
	rest, _, _ = strings.Cut(rest, "/")
	rest, _, _ = strings.Cut(rest, "?")
	if i := strings.LastIndexByte(rest, '@'); i >= 0 {
		rest = rest[i+1:]
	}
	if h, _, err := splitHostPort(rest); err == nil {
		rest = h
	}
	if domainRe.MatchString(rest) {
		return strings.TrimSuffix(rest, ".")
	}
	return ""
}

// sanitizeOutbound removes options a link or subscription must never
// control: key logging to arbitrary files ("masterKeyLog"), REALITY's debug
// output ("show") and Hysteria's congestion debug ("quicParams.debug"). It
// also maps REALITY fingerprints that do not always send the post-quantum
// key share to chrome, as links do.
func sanitizeOutbound(v any) {
	switch x := v.(type) {
	case map[string]any:
		delete(x, "masterKeyLog")
		// Hysteria's congestion debug switches on process-wide output.
		if qp, ok := x["quicParams"].(map[string]any); ok {
			delete(qp, "debug")
		}
		if rs, ok := x["realitySettings"].(map[string]any); ok {
			delete(rs, "show")
			if fp, ok := rs["fingerprint"].(string); ok {
				rs["fingerprint"] = realityFingerprint(fp)
			}
		}
		for _, child := range x {
			sanitizeOutbound(child)
		}
	case []any:
		for _, child := range x {
			sanitizeOutbound(child)
		}
	}
}
