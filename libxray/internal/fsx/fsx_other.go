//go:build !windows

package fsx

import "os"

// Replace renames src over dst (see fsx_windows.go for Windows).
func Replace(src, dst string) error { return os.Rename(src, dst) }
