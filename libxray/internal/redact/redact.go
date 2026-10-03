// Package redact takes addresses and host names out of log text: the logs
// stay on a device that may be searched, and error texts carry both.
package redact

import (
	"net/netip"
	"regexp"
	"strings"
	"unicode"
)

// Candidates only: netip decides, so times and version numbers stay.
var (
	ipv4Like = regexp.MustCompile(`\b(?:\d{1,3}\.){3}\d{1,3}\b`)
	ipv6Like = regexp.MustCompile(`(?i)[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?:%\w+)?`)
	nameLike = regexp.MustCompile(`[\p{L}\p{N}_-]+(?:\.[\p{L}\p{N}_-]+)+`)
)

// IPs replaces the IP addresses in text with "[IP]".
func IPs(text string) string {
	for _, re := range []*regexp.Regexp{ipv4Like, ipv6Like} {
		text = re.ReplaceAllStringFunc(text, func(s string) string {
			if _, err := netip.ParseAddr(s); err == nil {
				return "[IP]"
			}
			return s
		})
	}
	return text
}

// Hosts replaces what may name a host, dot-separated names ending in a
// word of two or more characters, with "[host]". File names go too.
func Hosts(text string) string {
	return nameLike.ReplaceAllStringFunc(text, func(s string) string {
		last := s[strings.LastIndexByte(s, '.')+1:]
		first := []rune(last)
		if len(first) < 2 || !unicode.IsLetter(first[0]) {
			return s
		}
		return "[host]"
	})
}
