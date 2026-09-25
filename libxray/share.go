package libxray

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"

	"github.com/xtls/xray-core/common/geodata"
)

// Profile is the portable description of one server, shared by the Android
// app and (later) iOS. Outbounds[0] is the proxy outbound tagged "proxy";
// any further entries are helpers it depends on (e.g. chained hops from a
// JSON subscription).
type Profile struct {
	Name      string            `json:"name"`
	Protocol  string            `json:"protocol"`
	Address   string            `json:"address"`
	Port      int               `json:"port"`
	Network   string            `json:"network"`
	Security  string            `json:"security"`
	Link      string            `json:"link,omitempty"`
	Outbounds []json.RawMessage `json:"outbounds"`
	// Set when the link asked to skip certificate verification. Xray no
	// longer supports that, so the app pins the server certificate instead
	// (FetchCertSha256 + PinCertificate) during import.
	NeedsCertPin bool `json:"needsCertPin,omitempty"`
	// CertPinSNI / CertPinQuic tell the app how to fetch that certificate.
	CertPinSNI  string `json:"certPinSni,omitempty"`
	CertPinQuic bool   `json:"certPinQuic,omitempty"`
}

// ParseLink parses one share link (vless://, vmess://, trojan://, ss://,
// hysteria2:// / hy2://) and returns the Profile as JSON.
func ParseLink(link string) (string, error) {
	p, err := parseLink(link)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(p)
	return string(out), err
}

// userError marks errors whose text is meant for the end user.
type userError struct{ msg string }

func (e *userError) Error() string { return e.msg }

func errf(format string, a ...any) error { return &userError{fmt.Sprintf(format, a...)} }

func parseLink(raw string) (*Profile, error) {
	link := strings.TrimSpace(raw)
	link = strings.Trim(link, "\u200b\ufeff\"'`<>")
	if link == "" {
		return nil, errf("пустая ссылка")
	}
	idx := strings.Index(link, "://")
	if idx <= 0 {
		return nil, errf("это не ссылка-ключ (нет «://»)")
	}
	scheme := strings.ToLower(link[:idx])
	var (
		p   *Profile
		err error
	)
	switch scheme {
	case "vless":
		p, err = parseVless(link)
	case "vmess":
		p, err = parseVmess(link)
	case "trojan":
		p, err = parseTrojan(link)
	case "ss":
		p, err = parseShadowsocks(link)
	case "hysteria2", "hy2":
		p, err = parseHysteria2(link)
	case "http", "https":
		return nil, errf("это ссылка на подписку, а не ключ — добавьте её как подписку")
	case "happ":
		return nil, errf("зашифрованные ссылки Happ не поддерживаются — нужна обычная ссылка или подписка")
	default:
		return nil, errf("протокол %s:// не поддерживается", scheme)
	}
	if err != nil {
		return nil, err
	}
	p.Link = link
	if p.Name == "" {
		p.Name = net.JoinHostPort(p.Address, strconv.Itoa(p.Port))
	}
	return p, nil
}

// ---------------------------------------------------------------------------
// Generic URL handling. Share links are often not strictly valid URLs
// (unescaped characters, base64 blobs), so parsing is deliberately lenient.

type shareURL struct {
	scheme   string
	userinfo string // percent-decoded
	host     string // without brackets
	port     int
	path     string // percent-decoded, without leading '/'
	query    params
	fragment string // percent-decoded
}

type params map[string]string

// get returns the first non-empty value for any of the keys (case-insensitive).
func (p params) get(keys ...string) string {
	for _, k := range keys {
		if v, ok := p[strings.ToLower(k)]; ok && v != "" {
			return v
		}
	}
	return ""
}

func (p params) has(key string) bool {
	_, ok := p[strings.ToLower(key)]
	return ok
}

func (p params) flag(keys ...string) bool {
	switch strings.ToLower(p.get(keys...)) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}

// unescape percent-decodes s. Unlike url.QueryUnescape it keeps '+' as is:
// base64 values (ECH configs, keys) frequently contain unescaped '+'.
func unescape(s string) string {
	if !strings.Contains(s, "%") {
		return s
	}
	if v, err := url.PathUnescape(s); err == nil {
		return v
	}
	// Invalid escapes: decode what we can, byte by byte.
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '%' && i+2 < len(s) {
			if v, err := strconv.ParseUint(s[i+1:i+3], 16, 8); err == nil {
				b.WriteByte(byte(v))
				i += 2
				continue
			}
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

func parseShareURL(link string) (*shareURL, error) {
	u := &shareURL{query: params{}}
	idx := strings.Index(link, "://")
	u.scheme = strings.ToLower(link[:idx])
	rest := link[idx+3:]

	if i := strings.IndexByte(rest, '#'); i >= 0 {
		u.fragment = strings.TrimSpace(unescape(rest[i+1:]))
		rest = rest[:i]
	}
	if i := strings.IndexByte(rest, '?'); i >= 0 {
		for _, kv := range strings.Split(rest[i+1:], "&") {
			if kv == "" {
				continue
			}
			k, v, _ := strings.Cut(kv, "=")
			k = strings.ToLower(unescape(k))
			if _, dup := u.query[k]; !dup {
				u.query[k] = unescape(v)
			}
		}
		rest = rest[:i]
	}
	if at := strings.LastIndexByte(rest, '@'); at >= 0 {
		u.userinfo = unescape(rest[:at])
		rest = rest[at+1:]
	}
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		u.path = unescape(rest[i+1:])
		rest = rest[:i]
	}
	host, port, err := splitHostPort(rest)
	if err != nil {
		return nil, err
	}
	u.host, u.port = host, port
	return u, nil
}

func splitHostPort(hp string) (string, int, error) {
	hp = strings.TrimSpace(hp)
	if hp == "" {
		return "", 0, errf("в ссылке нет адреса сервера")
	}
	var host, portStr string
	if strings.HasPrefix(hp, "[") {
		end := strings.IndexByte(hp, ']')
		if end < 0 {
			return "", 0, errf("неверный IPv6-адрес сервера")
		}
		host = hp[1:end]
		portStr = strings.TrimPrefix(hp[end+1:], ":")
	} else {
		i := strings.LastIndexByte(hp, ':')
		if i < 0 {
			return "", 0, errf("в ссылке нет порта сервера")
		}
		host, portStr = hp[:i], hp[i+1:]
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, errf("неверный порт сервера: %q", portStr)
	}
	if host == "" {
		return "", 0, errf("в ссылке нет адреса сервера")
	}
	return host, port, nil
}

func isIP(host string) bool { return net.ParseIP(host) != nil }

func splitList(s string) []string {
	var out []string
	for _, v := range strings.Split(s, ",") {
		if v = strings.TrimSpace(v); v != "" {
			out = append(out, v)
		}
	}
	return out
}

// ---------------------------------------------------------------------------
// Transport + security ("streamSettings"), shared by VLESS, VMess, Trojan.

type streamOptions struct {
	network    string // type=
	security   string // security=
	host       string // host= (HTTP Host / WS host)
	path       string
	headerType string
	mode       string // xhttp mode / grpc mode
	extra      string // xhttp extra (JSON)
	service    string // grpc serviceName
	authority  string // grpc authority
	sni        string
	fp         string
	alpn       string
	pbk        string
	sid        string
	spx        string
	pqv        string
	ech        string
	pcs        string
	vcn        string
	insecure   bool
}

func streamOptionsFromQuery(q params) streamOptions {
	return streamOptions{
		network:    q.get("type", "network", "net"),
		security:   q.get("security"),
		host:       q.get("host"),
		path:       q.get("path"),
		headerType: q.get("headerType"),
		mode:       q.get("mode"),
		extra:      q.get("extra"),
		service:    q.get("serviceName", "servicename"),
		authority:  q.get("authority"),
		sni:        q.get("sni", "peer", "serverName"),
		fp:         q.get("fp", "fingerprint"),
		alpn:       q.get("alpn"),
		pbk:        q.get("pbk", "publicKey", "password"),
		sid:        q.get("sid", "shortId"),
		spx:        q.get("spx", "spiderX"),
		pqv:        q.get("pqv", "mldsa65Verify"),
		ech:        q.get("ech", "echConfigList"),
		pcs:        q.get("pcs", "pinnedPeerCertSha256", "pinSHA256"),
		vcn:        q.get("vcn", "verifyPeerCertByName"),
		insecure:   q.flag("allowInsecure", "insecure", "skip-cert-verify"),
	}
}

func normalizeNetwork(n string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(n)) {
	case "", "tcp", "raw":
		return "raw", nil
	case "ws", "websocket":
		return "ws", nil
	case "httpupgrade":
		return "httpupgrade", nil
	case "grpc", "gun":
		return "grpc", nil
	case "xhttp", "splithttp":
		return "xhttp", nil
	case "h2", "http":
		return "", errf("транспорт HTTP/2 удалён из Xray — попросите у провайдера ключ с XHTTP")
	case "quic":
		return "", errf("транспорт QUIC удалён из Xray — попросите у провайдера ключ с XHTTP")
	case "kcp", "mkcp":
		return "", errf("транспорт mKCP не поддерживается")
	default:
		return "", errf("неизвестный транспорт %q", n)
	}
}

// buildStream returns streamSettings plus the normalized network/security
// and whether the link requested insecure TLS.
func buildStream(o streamOptions, address string, defaultSecurity string) (map[string]any, string, string, bool, error) {
	network, err := normalizeNetwork(o.network)
	if err != nil {
		return nil, "", "", false, err
	}
	stream := map[string]any{"network": network}

	host := o.host
	path := o.path
	switch network {
	case "raw":
		if strings.EqualFold(o.headerType, "http") {
			req := map[string]any{"path": []string{orDefault(path, "/")}}
			if hosts := splitList(host); len(hosts) > 0 {
				req["headers"] = map[string]any{"Host": hosts}
			}
			stream["rawSettings"] = map[string]any{"header": map[string]any{"type": "http", "request": req}}
		}
	case "ws":
		ws := map[string]any{"path": orDefault(path, "/")}
		if host != "" {
			ws["host"] = host
		}
		stream["wsSettings"] = ws
	case "httpupgrade":
		hu := map[string]any{"path": orDefault(path, "/")}
		if host != "" {
			hu["host"] = host
		}
		stream["httpupgradeSettings"] = hu
	case "grpc":
		g := map[string]any{"serviceName": orDefault(o.service, path)}
		if o.authority != "" {
			g["authority"] = o.authority
		}
		if strings.EqualFold(o.mode, "multi") {
			g["multiMode"] = true
		}
		stream["grpcSettings"] = g
	case "xhttp":
		x := map[string]any{"path": orDefault(path, "/")}
		if host != "" {
			x["host"] = host
		}
		if o.mode != "" {
			switch o.mode {
			case "auto", "packet-up", "stream-up", "stream-one":
				x["mode"] = o.mode
			default:
				return nil, "", "", false, errf("неизвестный режим XHTTP %q", o.mode)
			}
		}
		if strings.TrimSpace(o.extra) != "" {
			var extra map[string]any
			if err := json.Unmarshal([]byte(o.extra), &extra); err != nil {
				return nil, "", "", false, errf("параметр extra в ссылке повреждён")
			}
			x["extra"] = extra
		}
		stream["xhttpSettings"] = x
	}

	security := strings.ToLower(o.security)
	if security == "" {
		security = defaultSecurity
	}
	fp := strings.ToLower(orDefault(o.fp, "chrome"))
	serverName := o.sni
	if serverName == "" {
		// Xray itself falls back to Host / address; do it explicitly so the
		// config is self-describing.
		if h := firstOf(splitList(host)); h != "" && network != "raw" {
			serverName = h
		} else if !isIP(address) {
			serverName = address
		}
	}
	switch security {
	case "none":
	case "tls":
		tls := map[string]any{"fingerprint": fp}
		if serverName != "" {
			tls["serverName"] = serverName
		}
		if a := splitList(o.alpn); len(a) > 0 {
			tls["alpn"] = a
		}
		if o.pcs != "" {
			tls["pinnedPeerCertSha256"] = o.pcs
		}
		if o.vcn != "" {
			tls["verifyPeerCertByName"] = o.vcn
		}
		if o.ech != "" {
			tls["echConfigList"] = o.ech
		}
		stream["security"] = "tls"
		stream["tlsSettings"] = tls
	case "reality":
		fp = realityFingerprint(fp)
		if network != "raw" && network != "xhttp" && network != "grpc" {
			return nil, "", "", false, errf("REALITY работает только с RAW, XHTTP и gRPC")
		}
		if o.pbk == "" {
			return nil, "", "", false, errf("в ключе REALITY нет публичного ключа (pbk)")
		}
		r := map[string]any{"fingerprint": fp, "password": o.pbk, "shortId": o.sid}
		if serverName != "" {
			r["serverName"] = serverName
		}
		if o.spx != "" {
			r["spiderX"] = o.spx
		}
		if o.pqv != "" {
			r["mldsa65Verify"] = o.pqv
		}
		stream["security"] = "reality"
		stream["realitySettings"] = r
	case "xtls":
		return nil, "", "", false, errf("устаревший XTLS больше не поддерживается ядром — нужен ключ с REALITY или TLS")
	default:
		return nil, "", "", false, errf("неизвестный тип защиты %q", o.security)
	}
	needsPin := security == "tls" && o.insecure && o.pcs == ""
	return stream, network, security, needsPin, nil
}

// isPrivateAddress mirrors Xray's own rule: plaintext VLESS/Trojan is only
// allowed towards private IPs and private domains.
func isPrivateAddress(host string) bool {
	if ip := net.ParseIP(host); ip != nil {
		return geodata.GetPrivateIPMatcher().Match(ip)
	}
	return geodata.GetPrivateDomainMatcher().MatchAny(strings.TrimSuffix(strings.ToLower(host), "."))
}

var errPlaintext = errf("ключ без шифрования (нет TLS/REALITY): ядро Xray запрещает такие подключения — провайдер видел бы весь трафик. Попросите ключ с REALITY или TLS")

// realityFingerprint keeps only uTLS fingerprints that offer the
// X25519MLKEM768 key share. Current REALITY servers silently reject
// ClientHellos without it (old clients like outdated v2RayTun builds break
// exactly this way), so e.g. "edge" or "ios" from an old link is replaced by
// "chrome", which works with both old and new servers.
func realityFingerprint(fp string) string {
	// Current REALITY servers drop a hello without the X25519MLKEM768 key
	// share. These fingerprints always send it; "randomizednoalpn" only on
	// some process starts, so it is mapped to chrome like the rest.
	switch fp {
	case "chrome", "firefox", "safari":
		return fp
	}
	return "chrome"
}

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

func firstOf(v []string) string {
	if len(v) == 0 {
		return ""
	}
	return v[0]
}

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

// ---------------------------------------------------------------------------
// VLESS

func parseVless(link string) (*Profile, error) {
	u, err := parseShareURL(link)
	if err != nil {
		return nil, err
	}
	id := strings.TrimSpace(u.userinfo)
	if id == "" {
		return nil, errf("в ключе VLESS нет UUID")
	}
	enc := u.query.get("encryption")
	if enc == "" {
		enc = "none"
	}
	flow := u.query.get("flow")
	switch flow {
	case "", "xtls-rprx-vision", "xtls-rprx-vision-udp443":
	default:
		return nil, errf("устаревший flow %q больше не поддерживается ядром", flow)
	}
	opts := streamOptionsFromQuery(u.query)
	stream, network, security, needsPin, err := buildStream(opts, u.host, "none")
	if err != nil {
		return nil, err
	}
	if security == "none" && enc == "none" && !isPrivateAddress(u.host) {
		return nil, errPlaintext
	}
	user := map[string]any{"id": id, "encryption": enc, "level": 0}
	if flow != "" {
		user["flow"] = flow
	}
	outbound := map[string]any{
		"tag":      ProxyTag,
		"protocol": "vless",
		"settings": map[string]any{"vnext": []any{map[string]any{
			"address": u.host, "port": u.port, "users": []any{user},
		}}},
		"streamSettings": stream,
	}
	return &Profile{
		Name: u.fragment, Protocol: "vless", Address: u.host, Port: u.port,
		Network: network, Security: security, Outbounds: []json.RawMessage{mustJSON(outbound)},
		NeedsCertPin: needsPin, CertPinSNI: tlsServerName(stream),
	}, nil
}

// ---------------------------------------------------------------------------
// Trojan

func parseTrojan(link string) (*Profile, error) {
	u, err := parseShareURL(link)
	if err != nil {
		return nil, err
	}
	if u.userinfo == "" {
		return nil, errf("в ключе Trojan нет пароля")
	}
	opts := streamOptionsFromQuery(u.query)
	stream, network, security, needsPin, err := buildStream(opts, u.host, "tls")
	if err != nil {
		return nil, err
	}
	if security == "none" && !isPrivateAddress(u.host) {
		return nil, errPlaintext
	}
	outbound := map[string]any{
		"tag":      ProxyTag,
		"protocol": "trojan",
		"settings": map[string]any{"servers": []any{map[string]any{
			"address": u.host, "port": u.port, "password": u.userinfo, "level": 0,
		}}},
		"streamSettings": stream,
	}
	return &Profile{
		Name: u.fragment, Protocol: "trojan", Address: u.host, Port: u.port,
		Network: network, Security: security, Outbounds: []json.RawMessage{mustJSON(outbound)},
		NeedsCertPin: needsPin, CertPinSNI: tlsServerName(stream),
	}, nil
}

// ---------------------------------------------------------------------------
// VMess (v2rayN base64 JSON format, plus the URL form)

func parseVmess(link string) (*Profile, error) {
	body := link[len("vmess://"):]
	if i := strings.IndexByte(body, '#'); i >= 0 && strings.Contains(body[:i], "@") {
		return parseVmessURL(link)
	}
	if strings.Contains(body, "@") && strings.Contains(body, "?") {
		return parseVmessURL(link)
	}
	decoded, err := decodeBase64Loose(strings.SplitN(body, "#", 2)[0])
	if err != nil {
		return nil, errf("ключ VMess повреждён (не base64)")
	}
	var v map[string]any
	if err := json.Unmarshal(decoded, &v); err != nil {
		return nil, errf("ключ VMess повреждён (не JSON)")
	}
	str := func(k string) string {
		switch x := v[k].(type) {
		case string:
			return strings.TrimSpace(x)
		case float64:
			return strconv.FormatInt(int64(x), 10)
		case bool:
			if x {
				return "1"
			}
			return ""
		}
		return ""
	}
	address := str("add")
	port, err := strconv.Atoi(str("port"))
	if err != nil || port <= 0 || port > 65535 {
		return nil, errf("неверный порт в ключе VMess")
	}
	if address == "" || str("id") == "" {
		return nil, errf("в ключе VMess нет адреса или UUID")
	}
	network := str("net")
	opts := streamOptions{
		network:  network,
		security: str("tls"),
		host:     str("host"),
		path:     str("path"),
		sni:      str("sni"),
		fp:       str("fp"),
		alpn:     str("alpn"),
		pcs:      str("pcs"),
		insecure: str("allowInsecure") == "1" || str("insecure") == "1",
	}
	switch strings.ToLower(network) {
	case "grpc":
		opts.service = str("path")
		opts.mode = str("type")
		opts.authority = str("authority")
	case "xhttp", "splithttp":
		opts.mode = str("type")
		opts.extra = str("extra")
		if m := str("mode"); m != "" {
			opts.mode = m
		}
	default:
		opts.headerType = str("type")
	}
	if opts.security == "" || opts.security == "0" {
		opts.security = "none"
	}
	stream, netw, security, needsPin, err := buildStream(opts, address, "none")
	if err != nil {
		return nil, err
	}
	scy := orDefault(str("scy"), "auto")
	outbound := map[string]any{
		"tag":      ProxyTag,
		"protocol": "vmess",
		"settings": map[string]any{"vnext": []any{map[string]any{
			"address": address, "port": port,
			"users": []any{map[string]any{"id": str("id"), "security": scy, "level": 0}},
		}}},
		"streamSettings": stream,
	}
	return &Profile{
		Name: str("ps"), Protocol: "vmess", Address: address, Port: port,
		Network: netw, Security: security, Outbounds: []json.RawMessage{mustJSON(outbound)},
		NeedsCertPin: needsPin, CertPinSNI: tlsServerName(stream),
	}, nil
}

func parseVmessURL(link string) (*Profile, error) {
	u, err := parseShareURL(link)
	if err != nil {
		return nil, err
	}
	if u.userinfo == "" {
		return nil, errf("в ключе VMess нет UUID")
	}
	opts := streamOptionsFromQuery(u.query)
	stream, network, security, needsPin, err := buildStream(opts, u.host, "none")
	if err != nil {
		return nil, err
	}
	outbound := map[string]any{
		"tag":      ProxyTag,
		"protocol": "vmess",
		"settings": map[string]any{"vnext": []any{map[string]any{
			"address": u.host, "port": u.port,
			"users": []any{map[string]any{"id": u.userinfo, "security": orDefault(u.query.get("encryption"), "auto"), "level": 0}},
		}}},
		"streamSettings": stream,
	}
	return &Profile{
		Name: u.fragment, Protocol: "vmess", Address: u.host, Port: u.port,
		Network: network, Security: security, Outbounds: []json.RawMessage{mustJSON(outbound)},
		NeedsCertPin: needsPin, CertPinSNI: tlsServerName(stream),
	}, nil
}

// ---------------------------------------------------------------------------
// Shadowsocks (SIP002 and the legacy fully-base64 form)

var ssMethods = map[string]bool{
	"aes-128-gcm": true, "aes-256-gcm": true, "chacha20-poly1305": true, "chacha20-ietf-poly1305": true,
	"xchacha20-poly1305": true, "xchacha20-ietf-poly1305": true, "none": true, "plain": true,
	"2022-blake3-aes-128-gcm": true, "2022-blake3-aes-256-gcm": true, "2022-blake3-chacha20-poly1305": true,
}

func parseShadowsocks(link string) (*Profile, error) {
	body := link[len("ss://"):]
	name := ""
	if i := strings.IndexByte(body, '#'); i >= 0 {
		name = strings.TrimSpace(unescape(body[i+1:]))
		body = body[:i]
	}
	// Legacy: ss://base64(method:password@host:port)
	if !strings.Contains(body, "@") {
		plain := strings.SplitN(body, "?", 2)[0]
		plain = strings.TrimSuffix(plain, "/")
		decoded, err := decodeBase64Loose(plain)
		if err != nil {
			return nil, errf("ключ Shadowsocks повреждён")
		}
		body = string(decoded)
	}
	u, err := parseShareURL("ss://" + body)
	if err != nil {
		return nil, err
	}
	if plugin := u.query.get("plugin"); plugin != "" {
		return nil, errf("плагины Shadowsocks (%s) не поддерживаются", strings.SplitN(plugin, ";", 2)[0])
	}
	userinfo := u.userinfo
	method, password, ok := strings.Cut(userinfo, ":")
	if !ok || !ssMethods[strings.ToLower(method)] {
		if decoded, err := decodeBase64Loose(userinfo); err == nil {
			method, password, ok = strings.Cut(string(decoded), ":")
		}
	}
	method = strings.ToLower(method)
	if !ok || password == "" {
		return nil, errf("в ключе Shadowsocks нет метода или пароля")
	}
	if !ssMethods[method] {
		return nil, errf("метод шифрования Shadowsocks %q не поддерживается", method)
	}
	outbound := map[string]any{
		"tag":      ProxyTag,
		"protocol": "shadowsocks",
		"settings": map[string]any{"servers": []any{map[string]any{
			"address": u.host, "port": u.port, "method": method, "password": password, "level": 0,
		}}},
		"streamSettings": map[string]any{"network": "raw"},
	}
	if name == "" {
		name = u.fragment
	}
	return &Profile{
		Name: name, Protocol: "shadowsocks", Address: u.host, Port: u.port,
		Network: "raw", Security: "none", Outbounds: []json.RawMessage{mustJSON(outbound)},
	}, nil
}

// ---------------------------------------------------------------------------
// Hysteria2

func parseHysteria2(link string) (*Profile, error) {
	u, err := parseShareURL(link)
	if err != nil {
		return nil, err
	}
	q := u.query
	if hop := q.get("mport"); hop != "" {
		return nil, errf("переключение портов Hysteria2 (mport) пока не поддерживается")
	}
	sni := q.get("sni", "peer")
	if sni == "" && !isIP(u.host) {
		sni = u.host
	}
	tls := map[string]any{"alpn": orDefaultList(splitList(q.get("alpn")), []string{"h3"})}
	if sni != "" {
		tls["serverName"] = sni
	}
	pcs := q.get("pinSHA256", "pcs")
	if pcs != "" {
		tls["pinnedPeerCertSha256"] = pcs
	}
	stream := map[string]any{
		"network":          "hysteria",
		"hysteriaSettings": map[string]any{"version": 2, "auth": u.userinfo},
		"security":         "tls",
		"tlsSettings":      tls,
	}
	switch obfs := strings.ToLower(q.get("obfs")); obfs {
	case "":
	case "salamander":
		pw := q.get("obfs-password", "obfsPassword")
		if pw == "" {
			return nil, errf("в ключе Hysteria2 нет obfs-password")
		}
		stream["finalmask"] = map[string]any{"udp": []any{map[string]any{
			"type": "salamander", "settings": map[string]any{"password": pw},
		}}}
	default:
		return nil, errf("обфускация Hysteria2 %q не поддерживается", obfs)
	}
	outbound := map[string]any{
		"tag":            ProxyTag,
		"protocol":       "hysteria",
		"settings":       map[string]any{"version": 2, "address": u.host, "port": u.port},
		"streamSettings": stream,
	}
	insecure := q.flag("insecure", "allowInsecure")
	return &Profile{
		Name: u.fragment, Protocol: "hysteria2", Address: u.host, Port: u.port,
		Network: "hysteria", Security: "tls", Outbounds: []json.RawMessage{mustJSON(outbound)},
		NeedsCertPin: insecure && pcs == "", CertPinSNI: sni, CertPinQuic: true,
	}, nil
}

func orDefaultList(v, def []string) []string {
	if len(v) == 0 {
		return def
	}
	return v
}

func tlsServerName(stream map[string]any) string {
	if t, ok := stream["tlsSettings"].(map[string]any); ok {
		if s, ok := t["serverName"].(string); ok {
			return s
		}
	}
	return ""
}

// decodeBase64Loose accepts standard / URL-safe alphabets, with or without
// padding and with embedded whitespace.
func decodeBase64Loose(s string) ([]byte, error) {
	s = strings.Map(func(r rune) rune {
		switch r {
		case ' ', '\n', '\r', '\t':
			return -1
		}
		return r
	}, s)
	s = strings.TrimRight(s, "=")
	if s == "" {
		return nil, errors.New("empty")
	}
	if b, err := base64.RawStdEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	return base64.RawURLEncoding.DecodeString(s)
}

// PinCertificate returns profileJSON with pinnedPeerCertSha256 set on the
// proxy outbound's TLS settings. Used after FetchCertSha256 for links that
// asked for "allowInsecure".
func PinCertificate(profileJSON string, sha256Hex string) (string, error) {
	var p Profile
	if err := json.Unmarshal([]byte(profileJSON), &p); err != nil {
		return "", err
	}
	if len(p.Outbounds) == 0 {
		return "", errors.New("profile has no outbounds")
	}
	var ob map[string]any
	if err := json.Unmarshal(p.Outbounds[0], &ob); err != nil {
		return "", err
	}
	stream, _ := ob["streamSettings"].(map[string]any)
	if stream == nil {
		return "", errors.New("profile has no TLS settings")
	}
	tls, _ := stream["tlsSettings"].(map[string]any)
	if tls == nil {
		return "", errors.New("profile has no TLS settings")
	}
	if sha256Hex != "" {
		tls["pinnedPeerCertSha256"] = sha256Hex
	}
	// "" means the certificate is valid under the system roots: plain
	// verification applies and survives certificate renewals.
	p.Outbounds[0] = mustJSON(ob)
	p.NeedsCertPin = false
	out, err := json.Marshal(p)
	return string(out), err
}
