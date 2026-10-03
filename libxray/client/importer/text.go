// Package importer reads pasted or shared text as the Android app's
// ImportText does, and turns the keys in it into servers ready to save.
package importer

import (
	"regexp"
	"strings"
)

var (
	linkStart = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9+.\-]*://`)
	// Java's \S, which also excludes the vertical tab.
	linkAnywhere = regexp.MustCompile(`[A-Za-z][A-Za-z0-9+.\-]*://[^\t\n\v\f\r ]+`)
)

// Links returns the keys and links in text. A line that starts with a link
// is taken whole (names after "#" may contain spaces); inside other text,
// such as a messenger message, each link is picked out.
func Links(text string) []string {
	text = strings.TrimSpace(text)
	// A pasted subscription body (Xray JSON) is not a list of links.
	if strings.HasPrefix(text, "{") || strings.HasPrefix(text, "[") {
		return nil
	}
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	var links []string
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if linkStart.MatchString(line) {
			links = append(links, line)
			continue
		}
		for _, l := range linkAnywhere.FindAllString(line, -1) {
			links = append(links, strings.TrimRight(l, `.,;)»"'`))
		}
	}
	return links
}

// SubscriptionURL returns the subscription URL when text is a single
// http(s) link, else "".
func SubscriptionURL(text string) string {
	links := Links(text)
	if len(links) != 1 {
		return ""
	}
	l := strings.ToLower(links[0])
	if strings.HasPrefix(l, "https://") || strings.HasPrefix(l, "http://") {
		return links[0]
	}
	return ""
}
