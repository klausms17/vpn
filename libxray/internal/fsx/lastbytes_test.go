package fsx

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLastBytesStartsAtALine(t *testing.T) {
	path := filepath.Join(t.TempDir(), "log")
	if err := os.WriteFile(path, []byte("first line\nsecond\nthird\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for limit, want := range map[int]string{
		100: "first line\nsecond\nthird\n",
		24:  "first line\nsecond\nthird\n",
		23:  "second\nthird\n",
		13:  "second\nthird\n",
		12:  "third\n",
		3:   "",
	} {
		got, err := LastBytes(path, limit)
		if err != nil || string(got) != want {
			t.Errorf("limit %d: %q %v, want %q", limit, got, err, want)
		}
	}
	if _, err := LastBytes(filepath.Join(t.TempDir(), "missing"), 10); err == nil {
		t.Error("a missing file gave no error")
	}
}
