// Package applog is the app's own small log, as Android's AppLog: lines
// "MM-dd HH:mm:ss L message" appended to a file that moves to "<name>.1"
// once it is over MaxBytes. Never log keys, links, passwords, IPs or
// server names.
package applog

import (
	"bytes"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/klausms17/vpn/libxray/internal/fsx"
)

// MaxBytes is the size above which the log moves to "<name>.1".
const MaxBytes = 128 << 10

// Log is one log file. Each line opens and closes the file, so nothing
// holds it open and it can always be moved.
type Log struct {
	mu   sync.Mutex
	path string
	now  func() time.Time
}

// New logs to the file at path.
func New(path string) *Log { return &Log{path: path, now: time.Now} }

// Info, Warn and Error add a line.
func (l *Log) Info(msg string)  { l.line('I', msg) }
func (l *Log) Warn(msg string)  { l.line('W', msg) }
func (l *Log) Error(msg string) { l.line('E', msg) }

// Write takes lines from Go's log package, which wintun logs through.
func (l *Log) Write(p []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		l.line('I', line)
	}
	return len(p), nil
}

func (l *Log) line(level byte, msg string) {
	var b bytes.Buffer
	b.WriteString(l.now().Format("01-02 15:04:05"))
	b.WriteByte(' ')
	b.WriteByte(level)
	b.WriteByte(' ')
	b.WriteString(msg)
	b.WriteByte('\n')

	l.mu.Lock()
	defer l.mu.Unlock()
	// Logging must never stop the app: errors are dropped.
	if st, err := os.Stat(l.path); err == nil && st.Size() > MaxBytes {
		_ = fsx.Replace(l.path, l.path+".1")
	}
	f, err := os.OpenFile(l.path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return
	}
	_, _ = f.Write(b.Bytes())
	_ = f.Close()
}
