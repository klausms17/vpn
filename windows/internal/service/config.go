package service

import (
	"encoding/json"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/model"
)

// buildConfig makes the core's config for a server as Android does (Russian
// sites direct, no IPv6, the core logging warnings to logFile), with the
// Windows tunnel.
func buildConfig(logFile string) func(model.StoredProfile) (string, error) {
	return func(p model.StoredProfile) (string, error) {
		var outbounds []json.RawMessage
		if err := json.Unmarshal(p.Outbounds, &outbounds); err != nil {
			return "", err
		}
		opts, err := json.Marshal(libxray.BuildOptions{
			Outbounds: outbounds,
			Mode:      libxray.ModeRuDirect,
			LogLevel:  "warning",
			LogFile:   logFile,
			Tun:       true,
			Windows:   true,
		})
		if err != nil {
			return "", err
		}
		return libxray.BuildConfig(string(opts))
	}
}
