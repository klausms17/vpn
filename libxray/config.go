package libxray

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"regexp"
	"regexp/syntax"
	"slices"
	"strconv"
	"strings"
	"unicode"

	"github.com/klausms17/vpn/libxray/internal/privileged"
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

// Policy levels (see "policy" in buildConfig). Every profile outbound runs
// at levelProxy: prepareOutbounds resets the levels a subscription sets.
const (
	levelProxy  = 0
	levelDNS    = 1
	levelDirect = 2
)

// plainDNS answers the DNS query types the core does not resolve itself
// (see dns-out), over TCP through the proxy. A variable for tests.
var plainDNS = struct {
	addr string
	port int
}{"1.1.1.1", 53}

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
	// Programs, by file name ("qbittorrent.exe"), whose connections go
	// directly or through the proxy whatever they connect to. Xray finds
	// the program of a connection in the system's tables (Windows).
	DirectPrograms []string `json:"directPrograms"`
	ProxyPrograms  []string `json:"proxyPrograms"`

	LogLevel string `json:"logLevel"` // none|error|warning|info|debug
	LogFile  string `json:"logFile"`

	// Tun adds the TUN inbound fed by the Android VpnService fd.
	Tun    bool `json:"tun"`
	TunMTU int  `json:"tunMtu"`
	// Windows has Xray create and set up the wintun adapter itself instead
	// (see windowsTunSettings), with the rules Windows needs.
	Windows bool `json:"windows"`
	// SocksPort adds a 127.0.0.1 SOCKS inbound. Only for tests: the app
	// never opens local ports, because any app on the phone could use them
	// to detect the VPN or reach the proxy.
	SocksPort int `json:"socksPort"`
}

// BuildConfig turns BuildOptions (JSON) into a complete Xray config (JSON).
func BuildConfig(optionsJSON string) (configJSON string, err error) {
	defer recoverInto(&err)

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
func BuildProxyOnlyConfig(outboundsJSON string) (configJSON string, err error) {
	defer recoverInto(&err)

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
		if ob == nil {
			return nil, fmt.Errorf("outbound %d is not an object", i)
		}
		sanitizeOutbound(ob)
		for k, settings := range ob {
			if strings.EqualFold(k, "settings") {
				resetLevels(settings)
			}
		}
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
	// The Windows service runs the core as SYSTEM, with keys any user of
	// the PC may add.
	if o.Windows {
		if err := privileged.Check(o.Outbounds); err != nil {
			return nil, err
		}
	}
	outbounds, err := prepareOutbounds(o.Outbounds)
	if err != nil {
		return nil, err
	}
	// Vision refuses QUIC; see blockQUICToProxy.
	vision := visionFlow(outbounds[0].(map[string]any))
	var localNames []string
	if o.Windows {
		strategy := "UseIPv4"
		if o.IPv6 {
			strategy = "UseIP"
		}
		localNames = append(resolveServersLocally(outbounds, strategy), windowsDirectDomains...)
	}
	outbounds = append(outbounds,
		map[string]any{"tag": DirectTag, "protocol": "freedom", "settings": map[string]any{"userLevel": levelDirect}},
		map[string]any{"tag": blockTag, "protocol": "blackhole"},
		map[string]any{
			"tag":      dnsOutTag,
			"protocol": "dns",
			"settings": map[string]any{
				"userLevel": levelDNS,
				"rules": []any{
					// A and AAAA: answered by the DNS module (see buildDNS).
					map[string]any{"action": "hijack", "qType": "1,28"},
					// MX, TXT, SRV and NAPTR (SIP and XMPP apps, Minecraft
					// servers, mail setup) go as they are to a public resolver
					// over TCP through the proxy, never in the clear. An empty
					// answer would quietly break those apps.
					map[string]any{"action": "direct", "qType": "15,16,33,35"},
					// The rest gets an empty answer at once. HTTPS/SVCB records
					// carry ECH keys, and a browser using ECH hides the site
					// name that routing by domain needs. PTR is asked in passing
					// by common libraries (Java's getHostName on an IP), which
					// would then wait for the server, or its timeout.
					map[string]any{"action": "return"},
				},
				// Without it "direct" would ask the tunnel's own DNS address,
				// which nothing answers outside the tunnel.
				"rewriteAddress": plainDNS.addr,
				"rewritePort":    plainDNS.port,
				"rewriteNetwork": "tcp",
			},
			"streamSettings": map[string]any{"sockopt": map[string]any{"dialerProxy": ProxyTag}},
		},
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
		// An explicit name avoids interface enumeration, which Android
		// forbids for apps.
		settings := map[string]any{"name": "tun0", "mtu": mtu}
		if o.Windows {
			settings = windowsTunSettings(mtu)
		}
		inbounds = append(inbounds, map[string]any{
			"tag":      tunInboundTag,
			"protocol": "tun",
			"port":     0,
			"settings": settings,
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
		if o.Windows {
			rules = append(rules, windowsRules()...)
		}
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
	// 4. User rules: blocked sites, then programs, then sites.
	rules = append(rules, userRules(o.BlockRules, blockTag)...)
	for _, up := range []struct {
		names []string
		tag   string
	}{{o.DirectPrograms, DirectTag}, {o.ProxyPrograms, ProxyTag}} {
		var names []string
		for _, n := range up.names {
			if n = ProgramName(n); n != "" && !slices.Contains(names, n) {
				names = append(names, n)
			}
		}
		if len(names) > 0 {
			rules = append(rules, rule{"process": names, "outboundTag": up.tag})
		}
	}
	rules = append(rules, userRules(o.DirectRules, DirectTag)...)
	rules = append(rules, userRules(o.ProxyRules, ProxyTag)...)
	// 5. The mode itself. Known limitation: a UDP socket is routed once, by
	// its first packet (Xray's full-cone NAT keys a flow by the app's port
	// only). If a call, game or DHT socket first talks to a Russian IP, its
	// later packets to foreign peers leave directly too. Keying flows by
	// destination as well would break full-cone NAT, so it stays that way.
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
	if vision {
		rules = blockQUICToProxy(rules)
	}

	logLevel := o.LogLevel
	if logLevel == "" {
		logLevel = "warning"
	}
	// No access or DNS log: the log is for errors, not a record of what the
	// user visited (see redactedFileLog).
	logCfg := map[string]any{"loglevel": logLevel, "access": "none", "dnsLog": false}
	if o.LogFile != "" {
		logCfg["error"] = o.LogFile
	}

	return map[string]any{
		"log": logCfg,
		"policy": map[string]any{
			"levels": map[string]any{
				// Proxied connections get 15 idle minutes instead of Xray's 5.
				// Xray checks once per period, so an idle connection ends
				// after 15 to 30 awake minutes: push channels, IMAP IDLE and
				// SSH with a keepalive of up to 15 minutes are no longer cut
				// and redialed. The timer stands still while the phone
				// sleeps, and the server needs the same setting, or it still
				// closes them after 5 minutes. Not longer: UDP flows (calls,
				// and QUIC on servers without Vision) only end on this timer
				// and hold memory until then.
				strconv.Itoa(levelProxy): map[string]any{"connIdle": 900},
				// DNS flows are freed after seconds (each app query is its
				// own flow).
				strconv.Itoa(levelDNS): map[string]any{"connIdle": 10},
				// Direct flows keep Xray's 5 minutes: most are QUIC to Russian
				// sites, and a UDP flow only ends on this timer, holding its
				// socket and, after Stop, the old instance (see Stop).
				strconv.Itoa(levelDirect): map[string]any{"connIdle": 300},
			},
		},
		"dns":       buildDNS(o, localNames),
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"routing":   map[string]any{"domainStrategy": "AsIs", "rules": rules},
	}, nil
}

// localNames (Windows only) are resolved through the physical network's
// DNS servers first: the servers' own names and Windows' connectivity
// checks (see resolveServersLocally).
func buildDNS(o *BuildOptions, localNames []string) map[string]any {
	var servers []any
	if len(localNames) > 0 {
		servers = append(servers, map[string]any{
			"address":      "localhost",
			"domains":      localNames,
			"skipFallback": true,
		})
	}
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
		// A lookup that fails gets no answer at all from the core, so apps
		// wait out Android's resolver timeout. An answer that expired less
		// than an hour ago is returned at once instead (with a 1-second
		// TTL) and refreshed in the background, so names already seen by
		// the running core keep resolving while the server is unreachable,
		// including sites that then go direct. The hour also bounds the
		// cache: with 0, expired answers would never be removed.
		"serveStale":      true,
		"serveExpiredTTL": 3600,
		"servers":         servers,
	}
}

var domainRe = regexp.MustCompile(`^[a-z0-9\p{L}_]([a-z0-9\p{L}_-]*[a-z0-9\p{L}_])?(\.[a-z0-9\p{L}_]([a-z0-9\p{L}_-]*[a-z0-9\p{L}_])?)*\.?$`)

// userRules routes the domains and addresses of entries to tag.
func userRules(entries []string, tag string) []rule {
	var rules []rule
	domains, ips := splitUserRules(entries)
	if len(domains) > 0 {
		rules = append(rules, rule{"domain": domains, "outboundTag": tag})
	}
	if len(ips) > 0 {
		rules = append(rules, rule{"ip": ips, "outboundTag": tag})
	}
	return rules
}

// splitUserRules converts user entries into Xray domain and IP matchers,
// silently dropping anything invalid so a typo can never stop the VPN.
func splitUserRules(entries []string) (domains, ips []string) {
	for _, e := range entries {
		domain, ip := userRule(e)
		if domain != "" {
			domains = append(domains, domain)
		}
		if ip != "" {
			ips = append(ips, ip)
		}
	}
	return domains, ips
}

// UserRuleEntry checks a site the user typed for the rule lists of
// BuildOptions: a domain ("example.com", "*.example.com"), an address or a
// network, a link (its host is kept) or one of Xray's forms ("domain:",
// "full:", "keyword:", "regexp:"). It returns the entry as it is to be
// saved, or "" if it is none of those.
func UserRuleEntry(entry string) string {
	domain, ip := userRule(entry)
	switch {
	case ip != "":
		return ip
	case domain == "":
		return ""
	}
	if prefix, _, ok := strings.Cut(strings.TrimSpace(entry), ":"); ok && slices.Contains(xrayDomainForms, strings.ToLower(prefix)) {
		return domain
	}
	return strings.TrimPrefix(domain, "domain:")
}

var xrayDomainForms = []string{"domain", "full", "keyword", "regexp"}

// Bounds of a user rule. A domain name has at most 253 characters, and a
// keyword or a regexp longer than that is no site but a way to make the
// core's start slow and big; Xray compiles every regexp rule at each
// start, and a short pattern can still expand a lot ("a{1000}"). A link
// may be longer: only its host is kept.
const (
	maxRuleEntry   = 253
	maxRuleLink    = 2048
	maxRegexpInsts = 1000
)

// userRule converts one user entry into an Xray domain or IP matcher; both
// are "" for an entry that is not valid.
func userRule(e string) (domain, ip string) {
	e = strings.TrimSpace(e)
	if e == "" || len(e) > maxRuleLink || strings.HasPrefix(e, "#") {
		return "", ""
	}
	if prefix, rest, ok := strings.Cut(e, ":"); ok {
		switch prefix = strings.ToLower(prefix); prefix {
		case "domain", "full":
			if rest = strings.ToLower(rest); len(rest) <= maxRuleEntry && domainRe.MatchString(rest) {
				return prefix + ":" + rest, ""
			}
			return "", ""
		case "keyword":
			if rest = strings.ToLower(rest); rest != "" && len(rest) <= maxRuleEntry && printableASCII(rest) {
				return prefix + ":" + rest, ""
			}
			return "", ""
		case "regexp":
			// As typed: in lower case \D, \S and \W would mean their
			// opposites.
			if rest != "" && len(rest) <= maxRuleEntry && printableASCII(rest) && smallRegexp(rest) {
				return prefix + ":" + rest, ""
			}
			return "", ""
		case "http", "https":
			// A pasted URL: keep the host.
			if h := hostFromURL(strings.ToLower(e)); h != "" && len(h) <= maxRuleEntry {
				return "domain:" + h, ""
			}
			return "", ""
		}
	}
	if e = strings.ToLower(e); len(e) > maxRuleEntry {
		return "", ""
	}
	if pfx, err := netip.ParsePrefix(e); err == nil {
		if pfx.Addr().Is4In6() {
			// Xray reads "::ffff:1.2.3.4/128" as IPv4 and then refuses
			// the length: write it as IPv4, and drop a prefix shorter
			// than the mapped range.
			if pfx.Bits() < 96 {
				return "", ""
			}
			pfx = netip.PrefixFrom(pfx.Addr().Unmap(), pfx.Bits()-96)
		}
		return "", pfx.Masked().String()
	}
	if addr, err := netip.ParseAddr(e); err == nil {
		// Xray refuses a zone ("fe80::1%wlan0", even "fe80::1%wlan0/64"),
		// and link-local addresses never enter the tunnel anyway.
		if addr.Zone() == "" {
			return "", addr.Unmap().String()
		}
		return "", ""
	}
	e = strings.TrimPrefix(e, "*.")
	e = strings.TrimPrefix(e, ".")
	if domainRe.MatchString(e) {
		return "domain:" + strings.TrimSuffix(e, "."), ""
	}
	return "", ""
}

// ProgramName checks a program the user gave by its file name
// ("Telegram.exe") for the program lists of BuildOptions. It returns the
// name a process rule matches, or "" for anything that is not a plain file
// name. Xray compares names exactly, as Windows reports them, without a
// lower-case ".exe": "Telegram.exe" matches "Telegram", "GAME.EXE" only
// "GAME.EXE".
func ProgramName(name string) string {
	name = strings.TrimSuffix(strings.TrimSpace(name), ".exe")
	if name == "" || len(name) > 255 || strings.ContainsAny(name, `/\:*?"<>|`) || strings.TrimLeft(name, ".") == "" ||
		strings.ContainsFunc(name, unicode.IsControl) {
		return ""
	}
	return name
}

// printableASCII tells whether s has only visible ASCII characters, as
// domain names have.
func printableASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] <= ' ' || s[i] > '~' {
			return false
		}
	}
	return true
}

// smallRegexp tells whether expr compiles, in the syntax Xray uses, to at
// most maxRegexpInsts instructions.
func smallRegexp(expr string) bool {
	re, err := syntax.Parse(expr, syntax.Perl)
	if err != nil {
		return false
	}
	prog, err := syntax.Compile(re.Simplify())
	return err == nil && len(prog.Inst) <= maxRegexpInsts
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
		// Keys are matched like Xray's JSON loader does: case-insensitively,
		// so "MasterKeyLog" or "Debug" cannot slip through.
		for k, child := range x {
			switch {
			case strings.EqualFold(k, "masterKeyLog"):
				delete(x, k)
				continue
			case strings.EqualFold(k, "quicParams"):
				// Hysteria's congestion debug switches on process-wide output.
				if qp, ok := child.(map[string]any); ok {
					deleteFold(qp, "debug")
				}
			case strings.EqualFold(k, "realitySettings"):
				if rs, ok := child.(map[string]any); ok {
					deleteFold(rs, "show")
					for fk, fv := range rs {
						if fp, ok := fv.(string); ok && strings.EqualFold(fk, "fingerprint") {
							rs[fk] = realityFingerprint(fp)
						}
					}
				}
			}
			sanitizeOutbound(child)
		}
	case []any:
		for _, child := range x {
			sanitizeOutbound(child)
		}
	}
}

// resetLevels puts every user level in an outbound's settings on
// levelProxy, so the policy buildConfig sets for proxied connections
// applies whatever a subscription wrote. Its own level would otherwise get
// Xray's defaults or, as level 1, the 10-second idle meant for DNS.
func resetLevels(v any) {
	switch x := v.(type) {
	case map[string]any:
		for k, child := range x {
			if strings.EqualFold(k, "level") || strings.EqualFold(k, "userLevel") {
				x[k] = levelProxy
				continue
			}
			resetLevels(child)
		}
	case []any:
		for _, child := range x {
			resetLevels(child)
		}
	}
}

// visionFlow reports whether ob is VLESS with the plain Vision flow, which
// refuses UDP to port 443. "xtls-rprx-vision-udp443" carries QUIC and does
// not count.
func visionFlow(ob map[string]any) bool {
	if proto, _ := getFold(ob, "protocol").(string); !strings.EqualFold(proto, "vless") {
		return false
	}
	settings, _ := getFold(ob, "settings").(map[string]any)
	flows := []any{getFold(settings, "flow")} // the flat form
	vnext, _ := getFold(settings, "vnext").([]any)
	for _, v := range vnext {
		server, _ := v.(map[string]any)
		users, _ := getFold(server, "users").([]any)
		for _, u := range users {
			user, _ := u.(map[string]any)
			flows = append(flows, getFold(user, "flow"))
		}
	}
	for _, f := range flows {
		if f == "xtls-rprx-vision" {
			return true
		}
	}
	return false
}

// blockQUICToProxy puts before every rule that sends traffic to the proxy
// a copy that drops UDP to port 443 instead. A Vision server refuses QUIC,
// but only after a full REALITY handshake for every attempt, so each try
// by Chrome, YouTube or Play services costs several empty TLS sessions
// (CPU, log noise and a pattern DPI can see). Apps fall back to TCP either
// way. QUIC that goes direct is left alone.
func blockQUICToProxy(rules []rule) []rule {
	out := make([]rule, 0, len(rules)+4)
	for _, r := range rules {
		// The DNS module's own rules (inboundTag) never carry QUIC.
		if r["outboundTag"] == ProxyTag && r["inboundTag"] == nil {
			quic := rule{}
			for k, v := range r {
				quic[k] = v
			}
			quic["network"] = "udp"
			quic["port"] = "443"
			quic["outboundTag"] = blockTag
			out = append(out, quic)
		}
		out = append(out, r)
	}
	return out
}

// getFold returns m's value for key, matching keys case-insensitively as
// Xray's JSON loader does.
func getFold(m map[string]any, key string) any {
	if v, ok := m[key]; ok {
		return v
	}
	for k, v := range m {
		if strings.EqualFold(k, key) {
			return v
		}
	}
	return nil
}

// deleteFold removes every key equal to key under Unicode case folding.
func deleteFold(m map[string]any, key string) {
	for k := range m {
		if strings.EqualFold(k, key) {
			delete(m, k)
		}
	}
}
