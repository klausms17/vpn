package subscription

import (
	"strconv"
	"strings"
)

// Usage is what a panel's "subscription-userinfo" header says: bytes used
// and allowed, and when the subscription ends (Unix seconds); 0 is
// unknown.
type Usage struct {
	Used, Total, Expire int64
}

// maxExpire is the last second of the year 9999: a later expiry is no
// date a window can show, so it counts as unknown.
const maxExpire = 253402300799

// ParseUsage reads "upload=1; download=2; total=3; expire=4", as the
// Android app's servers screen does: entries that are not numbers are
// skipped.
func ParseUsage(userInfo string) Usage {
	values := map[string]int64{}
	for part := range strings.SplitSeq(userInfo, ";") {
		key, value, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		n, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
		if err != nil {
			continue
		}
		values[strings.ToLower(strings.TrimSpace(key))] = n
	}
	u := Usage{Used: values["upload"] + values["download"], Total: values["total"], Expire: values["expire"]}
	if u.Expire < 0 || u.Expire > maxExpire {
		u.Expire = 0
	}
	return u
}
