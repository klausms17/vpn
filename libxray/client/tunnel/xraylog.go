package tunnel

import (
	"bytes"
	"io"
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
		if end, err := LastBytes(old, keepBytes); err == nil {
			_ = os.WriteFile(old, end, 0o600)
		}
	}
	st, err := os.Stat(path)
	if err != nil || st.Size() <= maxBytes {
		return false
	}
	end, err := LastBytes(path, keepBytes)
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

// LastBytes returns at most the last limit bytes of the file at path,
// starting at a line.
func LastBytes(path string, limit int) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	// One byte more, to see whether the kept part begins a line.
	start := max(st.Size()-int64(limit)-1, 0)
	buf := make([]byte, st.Size()-start)
	if _, err := f.ReadAt(buf, start); err != nil && err != io.EOF {
		return nil, err
	}
	if start > 0 {
		buf = buf[bytes.IndexByte(buf, '\n')+1:]
	}
	return buf, nil
}
