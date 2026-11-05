//go:build !ios

package libxray

func startGC() func() { return func() {} }
