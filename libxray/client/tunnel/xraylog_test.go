package tunnel

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func lines(from, to int) string {
	var b strings.Builder
	for i := from; i <= to; i++ {
		fmt.Fprintf(&b, "line %04d\n", i)
	}
	return b.String()
}

func read(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestASmallLogStaysAsItIs(t *testing.T) {
	dir := t.TempDir()
	log := filepath.Join(dir, "xray.log")
	os.WriteFile(log, []byte(lines(1, 10)), 0o600)
	if TrimLog(log, 1000, 100) {
		t.Error("cut")
	}
	if read(t, log) != lines(1, 10) {
		t.Error("changed")
	}
	if _, err := os.Stat(log + ".1"); err == nil {
		t.Error("old file made")
	}
	// No log yet: nothing to do.
	if TrimLog(filepath.Join(dir, "none.log"), LogMaxBytes, LogKeepBytes) {
		t.Error("cut a missing log")
	}
}

func TestABigLogKeepsItsEndInTheOldFileAndStartsEmpty(t *testing.T) {
	dir := t.TempDir()
	log := filepath.Join(dir, "xray.log")
	os.WriteFile(log, []byte(lines(1, 200)), 0o600)
	os.WriteFile(log+".1", []byte("older\n"), 0o600)
	if !TrimLog(log, 1000, 105) {
		t.Fatal("not cut")
	}
	if read(t, log) != "" {
		t.Error("not emptied")
	}
	// The last 105 bytes start mid-line: only whole lines are kept.
	if got := read(t, log+".1"); got != lines(191, 200) {
		t.Errorf("kept %q", got)
	}
	if _, err := os.Stat(log + ".1.tmp"); err == nil {
		t.Error("temp file left")
	}
}

func TestTheCoreKeepsWritingAtTheStartAfterACut(t *testing.T) {
	log := filepath.Join(t.TempDir(), "xray.log")
	os.WriteFile(log, []byte(lines(1, 200)), 0o600)
	// The core holds the file open for appending, as Xray does.
	core, err := os.OpenFile(log, os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer core.Close()
	if !TrimLog(log, 1000, 100) {
		t.Fatal("not cut")
	}
	core.WriteString("after\n")
	if got := read(t, log); got != "after\n" {
		t.Errorf("log %q", got)
	}
}

func TestAnOversizedOldFileIsCutToo(t *testing.T) {
	log := filepath.Join(t.TempDir(), "xray.log")
	os.WriteFile(log, []byte("small\n"), 0o600)
	os.WriteFile(log+".1", []byte(lines(1, 300)), 0o600)
	if TrimLog(log, 1000, 100) {
		t.Error("cut the small log")
	}
	if got := read(t, log+".1"); got != lines(291, 300) {
		t.Errorf("old %q", got)
	}
	if read(t, log) != "small\n" {
		t.Error("log changed")
	}
}
