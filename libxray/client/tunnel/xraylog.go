package tunnel

import (
	"os"
	"sync"

	"github.com/klausms17/vpn/libxray/internal/fsx"
)

// The core's log is cut above LogMaxBytes; its last LogKeepBytes move to
// "<name>.1". The core writes it for as long as the tunnel runs, which can
// be weeks, and while a server is down it logs every failed DNS lookup.
const (
	LogMaxBytes  = 512 << 10
	LogKeepBytes = 256 << 10
)

var trimMu sync.Mutex

// TrimLog cuts the log at path when it is over maxBytes, as Android's
// XrayLog does: its last keepBytes (from a line start) move to "<path>.1"
// and the file is emptied in place. That is safe while the core writes,
// as it appends, so its next line starts the empty file. A "<path>.1" over
// maxBytes is cut to its end as well. It reports whether the log was cut;
// a log that cannot be cut never stops the tunnel, so errors are ignored.
func TrimLog(path string, maxBytes int64, keepBytes int) bool {
	trimMu.Lock()
	defer trimMu.Unlock()
	old := path + ".1"
	if st, err := os.Stat(old); err == nil && st.Size() > maxBytes {
		if end, err := fsx.LastBytes(old, keepBytes); err == nil {
			_ = os.WriteFile(old, end, 0o600)
		}
	}
	st, err := os.Stat(path)
	if err != nil || st.Size() <= maxBytes {
		return false
	}
	end, err := fsx.LastBytes(path, keepBytes)
	if err != nil {
		return false
	}
	tmp := old + ".tmp"
	if err := os.WriteFile(tmp, end, 0o600); err != nil {
		return false
	}
	if err := fsx.Replace(tmp, old); err != nil {
		_ = os.Remove(tmp)
		return false
	}
	return os.Truncate(path, 0) == nil
}
