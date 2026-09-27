package libxray

import (
	"bytes"
	"encoding/json"
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"
)

// SubscriptionResult is returned (as JSON) by ParseSubscription.
type SubscriptionResult struct {
	Profiles []*Profile `json:"profiles"`
	// Human readable reasons for entries that could not be imported.
	Errors []string `json:"errors,omitempty"`
	// Messages the panel sent as fake servers instead of real ones
	// ("Subscription expired", "Limit of devices reached", ...). A result
	// can hold only notices and no profiles.
	Notices []string `json:"notices,omitempty"`
}

// ageArmor starts a body encrypted with age: a response rule of the panel
// encrypts the list for one particular app.
const ageArmor = "-----BEGIN AGE ENCRYPTED FILE-----"

var errEncrypted = errf("подписка зашифрована для другого приложения — попросите владельца выдать обычную ссылку")

// ParseSubscription understands the formats subscription panels serve:
// base64 list of links, plain list of links, and Xray JSON configs (a single
// config or an array of them, as served to Happ/v2rayNG).
func ParseSubscription(body []byte) (resultJSON string, err error) {
	defer recoverInto(&err)

	res, err := parseSubscription(body)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(res)
	return string(out), err
}

func parseSubscription(body []byte) (*SubscriptionResult, error) {
	body = bytes.TrimSpace(bytes.TrimPrefix(body, []byte("\xef\xbb\xbf")))
	if len(body) == 0 {
		return nil, errf("подписка пустая")
	}
	if bytes.HasPrefix(body, []byte(ageArmor)) {
		return nil, errEncrypted
	}
	res := &SubscriptionResult{}
	if body[0] == '{' || body[0] == '[' {
		if err := parseJSONConfigs(body, res); err != nil {
			return nil, err
		}
	} else {
		text := string(body)
		if !strings.Contains(text, "://") {
			decoded, err := decodeBase64Loose(text)
			if err != nil || !utf8.Valid(decoded) {
				return nil, errf("не удалось разобрать подписку: это не список ключей")
			}
			text = string(decoded)
			if strings.HasPrefix(strings.TrimSpace(text), ageArmor) {
				return nil, errEncrypted
			}
			if t := strings.TrimSpace(text); strings.HasPrefix(t, "{") || strings.HasPrefix(t, "[") {
				if err := parseJSONConfigs([]byte(t), res); err != nil {
					return nil, err
				}
				text = ""
			}
		}
		n := 0 // entry number, for error messages
		for _, line := range strings.FieldsFunc(text, func(r rune) bool { return r == '\n' || r == '\r' }) {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
				continue
			}
			n++
			line = stripServerDescription(line)
			if notice, ok := placeholderNotice(line); ok {
				res.addNotice(notice)
				continue
			}
			p, err := parseLink(line)
			if err != nil {
				res.addError(n, line, err)
				continue
			}
			res.Profiles = append(res.Profiles, p)
		}
	}
	if len(res.Profiles) == 0 {
		if len(res.Notices) > 0 {
			// Not an error: the panel is talking to the user (expired,
			// device limit, ...). The caller keeps its old servers.
			res.Profiles = []*Profile{}
			return res, nil
		}
		if len(res.Errors) > 0 {
			return nil, errf("в подписке нет подходящих серверов: %s", res.Errors[0])
		}
		return nil, errf("в подписке нет серверов")
	}
	return res, nil
}

func (r *SubscriptionResult) addNotice(text string) {
	text = strings.TrimSpace(text)
	if text == "" || len(r.Notices) >= 20 || slices.Contains(r.Notices, text) {
		return
	}
	r.Notices = append(r.Notices, text)
}

// Remnawave answers with fake servers when it has something to say instead
// (subscription expired or disabled, device limit reached, app not
// supported, ...): VLESS entries at 0.0.0.0:1 with the all-zero UUID, named
// after the message.
const zeroUUID = "00000000-0000-0000-0000-000000000000"

func isPlaceholder(address string, port int, id string) bool {
	return (address == "0.0.0.0" && port == 1) || strings.EqualFold(strings.TrimSpace(id), zeroUUID)
}

// placeholderNotice checks a share link. It returns the link's remark when
// the link is such a fake server.
func placeholderNotice(link string) (string, bool) {
	if strings.Index(link, "://") <= 0 {
		return "", false
	}
	u, err := parseShareURL(link)
	if err != nil || !isPlaceholder(u.host, u.port, u.userinfo) {
		return "", false
	}
	return u.fragment, true
}

// placeholderProfile checks a profile from a JSON config the same way.
func placeholderProfile(p *Profile) bool {
	var ob struct {
		Settings struct {
			Vnext []struct {
				Users []struct {
					ID string `json:"id"`
				} `json:"users"`
			} `json:"vnext"`
		} `json:"settings"`
	}
	id := ""
	if len(p.Outbounds) > 0 && json.Unmarshal(p.Outbounds[0], &ob) == nil &&
		len(ob.Settings.Vnext) > 0 && len(ob.Settings.Vnext[0].Users) > 0 {
		id = ob.Settings.Vnext[0].Users[0].ID
	}
	return isPlaceholder(p.Address, p.Port, id)
}

// addError records why entry n was skipped. The message names the entry
// and never quotes it: a link carries its UUID or password, and these
// messages reach the app log.
func (r *SubscriptionResult) addError(n int, entry string, err error) {
	if len(r.Errors) >= 20 {
		return
	}
	r.Errors = append(r.Errors, fmt.Sprintf("%s: %v", entryLabel(n, entry), err))
}

var linkScheme = regexp.MustCompile(`^[a-z0-9+.-]{1,16}$`)

// entryLabel names entry n of a subscription by the link's remark, else by
// its number and scheme.
func entryLabel(n int, entry string) string {
	scheme, rest, isLink := strings.Cut(entry, "://")
	if !isLink {
		return fmt.Sprintf("строка %d", n)
	}
	if _, remark, ok := strings.Cut(rest, "#"); ok {
		if remark = strings.TrimSpace(unescape(remark)); remark != "" {
			if r := []rune(remark); len(r) > 60 {
				remark = string(r[:60]) + "…"
			}
			return remark
		}
	}
	if scheme = strings.ToLower(scheme); linkScheme.MatchString(scheme) {
		return fmt.Sprintf("ключ %d (%s)", n, scheme)
	}
	return fmt.Sprintf("ключ %d", n)
}

var proxyProtocols = map[string]bool{
	"vless": true, "vmess": true, "trojan": true, "shadowsocks": true, "hysteria": true,
	"wireguard": true, "socks": true, "http": true,
}

func parseJSONConfigs(body []byte, res *SubscriptionResult) error {
	var configs []map[string]any
	if body[0] == '[' {
		if err := json.Unmarshal(body, &configs); err != nil {
			return errf("подписка повреждена (JSON)")
		}
	} else {
		var single map[string]any
		if err := json.Unmarshal(body, &single); err != nil {
			return errf("подписка повреждена (JSON)")
		}
		configs = []map[string]any{single}
	}
	for i, cfg := range configs {
		remarks, _ := cfg["remarks"].(string)
		name := remarks
		if name == "" {
			name = fmt.Sprintf("Сервер %d", i+1)
		}
		profiles, err := profilesFromConfig(cfg, name)
		if err != nil {
			res.Errors = append(res.Errors, fmt.Sprintf("%s: %v", name, err))
			continue
		}
		for _, p := range profiles {
			if placeholderProfile(p) {
				res.addNotice(remarks)
				continue
			}
			res.Profiles = append(res.Profiles, p)
		}
	}
	return nil
}

// profilesFromConfig extracts one profile per "root" proxy outbound of an
// Xray JSON config, together with every outbound it chains through.
func profilesFromConfig(cfg map[string]any, name string) ([]*Profile, error) {
	rawList, ok := cfg["outbounds"].([]any)
	if !ok || len(rawList) == 0 {
		if _, singBox := cfg["route"]; singBox {
			return nil, errf("это конфигурация sing-box, а нужна Xray")
		}
		return nil, errf("в конфигурации нет outbounds")
	}
	byTag := map[string]map[string]any{}
	var all []map[string]any
	for _, x := range rawList {
		ob, ok := x.(map[string]any)
		if !ok {
			continue
		}
		if _, singBox := ob["type"]; singBox && ob["protocol"] == nil {
			return nil, errf("это конфигурация sing-box, а нужна Xray")
		}
		all = append(all, ob)
		if tag, _ := ob["tag"].(string); tag != "" {
			byTag[tag] = ob
		}
	}
	referenced := map[string]bool{}
	for _, ob := range all {
		for _, t := range chainRefs(ob) {
			referenced[t] = true
		}
	}
	var roots []map[string]any
	for _, ob := range all {
		proto, _ := ob["protocol"].(string)
		tag, _ := ob["tag"].(string)
		if proxyProtocols[proto] && !referenced[tag] {
			roots = append(roots, ob)
		}
	}
	if len(roots) == 0 {
		return nil, errf("в конфигурации нет прокси-серверов")
	}

	var profiles []*Profile
	for n, root := range roots {
		// Collect the chain (breadth first, cycle safe).
		chain := []map[string]any{root}
		seen := map[string]bool{}
		if tag, _ := root["tag"].(string); tag != "" {
			seen[tag] = true
		}
		for i := 0; i < len(chain); i++ {
			for _, ref := range chainRefs(chain[i]) {
				if seen[ref] {
					continue
				}
				dep, ok := byTag[ref]
				if !ok {
					return nil, errf("цепочка ссылается на отсутствующий outbound %q", ref)
				}
				seen[ref] = true
				chain = append(chain, dep)
			}
		}
		outbounds, err := retagChain(chain)
		if err != nil {
			return nil, err
		}
		p := &Profile{Name: name, Outbounds: outbounds}
		if len(roots) > 1 {
			p.Name = fmt.Sprintf("%s #%d", name, n+1)
		}
		fillSummary(p, root)
		// A root that skipped certificate checks is pinned on import.
		if ss, ok := root["streamSettings"].(map[string]any); ok {
			if tls, ok := ss["tlsSettings"].(map[string]any); ok {
				if insecure, _ := tls["allowInsecure"].(bool); insecure {
					p.NeedsCertPin = true
					p.CertPinSNI, _ = tls["serverName"].(string)
					if nw, _ := ss["network"].(string); nw == "hysteria" {
						p.CertPinQuic = true
					}
				}
			}
		}
		profiles = append(profiles, p)
	}
	return profiles, nil
}

// chainRefs lists the outbound tags ob dials through.
func chainRefs(ob map[string]any) []string {
	var refs []string
	if ss, ok := ob["streamSettings"].(map[string]any); ok {
		if so, ok := ss["sockopt"].(map[string]any); ok {
			if t, _ := so["dialerProxy"].(string); t != "" {
				refs = append(refs, t)
			}
		}
	}
	if ps, ok := ob["proxySettings"].(map[string]any); ok {
		if t, _ := ps["tag"].(string); t != "" {
			refs = append(refs, t)
		}
	}
	return refs
}

// retagChain gives the root the "proxy" tag and moves helpers off the tags
// the app reserves for itself, rewriting references accordingly.
func retagChain(chain []map[string]any) ([]json.RawMessage, error) {
	rename := map[string]string{}
	rootTag, _ := chain[0]["tag"].(string)
	if rootTag != "" {
		rename[rootTag] = ProxyTag
	}
	for i, ob := range chain[1:] {
		tag, _ := ob["tag"].(string)
		switch tag {
		case ProxyTag, DirectTag, blockTag, dnsOutTag:
			rename[tag] = "hop-" + strconv.Itoa(i+1) + "-" + tag
		}
	}
	var out []json.RawMessage
	for i, ob := range chain {
		// Deep copy through JSON so the source map is never mutated.
		var c map[string]any
		if err := json.Unmarshal(mustJSON(ob), &c); err != nil {
			return nil, err
		}
		if i == 0 {
			c["tag"] = ProxyTag
		} else if t, _ := c["tag"].(string); rename[t] != "" {
			c["tag"] = rename[t]
		}
		if ss, ok := c["streamSettings"].(map[string]any); ok {
			if so, ok := ss["sockopt"].(map[string]any); ok {
				if t, _ := so["dialerProxy"].(string); rename[t] != "" {
					so["dialerProxy"] = rename[t]
				}
			}
		}
		// Xray removed "proxySettings": express the hop as the equivalent
		// sockopt.dialerProxy so such subscriptions keep working.
		if ps, ok := c["proxySettings"].(map[string]any); ok {
			if t, _ := ps["tag"].(string); t != "" {
				if rename[t] != "" {
					t = rename[t]
				}
				ss, _ := c["streamSettings"].(map[string]any)
				if ss == nil {
					ss = map[string]any{}
					c["streamSettings"] = ss
				}
				so, _ := ss["sockopt"].(map[string]any)
				if so == nil {
					so = map[string]any{}
					ss["sockopt"] = so
				}
				if _, set := so["dialerProxy"]; !set {
					so["dialerProxy"] = t
				}
			}
			delete(c, "proxySettings")
		}
		// Xray also removed "allowInsecure". The root server gets its
		// certificate pinned instead (see profilesFromConfig); a hop cannot.
		if ss, ok := c["streamSettings"].(map[string]any); ok {
			if tls, ok := ss["tlsSettings"].(map[string]any); ok {
				if insecure, _ := tls["allowInsecure"].(bool); insecure && i > 0 {
					return nil, errf("цепочка серверов с отключённой проверкой сертификата не поддерживается")
				}
				delete(tls, "allowInsecure")
			}
		}
		out = append(out, mustJSON(c))
	}
	return out, nil
}

// fillSummary sets protocol/address/port/network/security for display.
func fillSummary(p *Profile, ob map[string]any) {
	p.Protocol, _ = ob["protocol"].(string)
	if p.Protocol == "hysteria" {
		p.Protocol = "hysteria2"
	}
	settings, _ := ob["settings"].(map[string]any)
	pick := func(m map[string]any) {
		if a, ok := m["address"].(string); ok {
			p.Address = a
		}
		if port, ok := m["port"].(float64); ok {
			p.Port = int(port)
		}
	}
	if settings != nil {
		pick(settings)
		for _, key := range []string{"vnext", "servers", "peers"} {
			if list, ok := settings[key].([]any); ok && len(list) > 0 {
				if m, ok := list[0].(map[string]any); ok {
					pick(m)
					if ep, ok := m["endpoint"].(string); ok && p.Address == "" {
						if h, port, err := splitHostPort(ep); err == nil {
							p.Address, p.Port = h, port
						}
					}
				}
			}
		}
	}
	p.Network, p.Security = "raw", "none"
	if ss, ok := ob["streamSettings"].(map[string]any); ok {
		if n, ok := ss["network"].(string); ok && n != "" {
			if nn, err := normalizeNetwork(n); err == nil {
				p.Network = nn
			} else {
				p.Network = n
			}
		}
		if s, ok := ss["security"].(string); ok && s != "" {
			p.Security = s
		}
	}
}
