package fsx

import (
	"bytes"
	"io"
	"os"
)

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
	if st.Size() > int64(limit) {
		// buf starts a byte before the kept part: what follows its first
		// line end is kept, so that no half line remains.
		i := bytes.IndexByte(buf, '\n')
		if i < 0 {
			return nil, nil
		}
		buf = buf[i+1:]
	}
	return buf, nil
}
