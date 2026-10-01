//go:build windows

package fsx

import (
	"errors"
	"os"
	"time"

	"golang.org/x/sys/windows"
)

// Replace renames src over dst. On Windows an antivirus or the search
// indexer reading dst makes the rename fail for a moment, so it retries for
// about two seconds.
func Replace(src, dst string) error {
	var err error
	for range 20 {
		if err = os.Rename(src, dst); err == nil || !inUse(err) {
			return err
		}
		time.Sleep(100 * time.Millisecond)
	}
	return err
}

func inUse(err error) bool {
	return errors.Is(err, windows.ERROR_ACCESS_DENIED) ||
		errors.Is(err, windows.ERROR_SHARING_VIOLATION) ||
		errors.Is(err, windows.ERROR_LOCK_VIOLATION)
}
