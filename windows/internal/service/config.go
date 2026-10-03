package service

import (
	"encoding/json"

	"github.com/klausms17/vpn/libxray"
	"github.com/klausms17/vpn/libxray/client/model"
	"github.com/klausms17/vpn/windows/internal/ipc"
)

// buildConfig makes the core's config for a server as Android does (no
// IPv6, the core logging warnings to logFile), with the Windows tunnel and
// the user's settings as they are when the tunnel starts.
func buildConfig(logFile string, settings func() ipc.Settings) func(model.StoredProfile) (string, error) {
	return func(p model.StoredProfile) (string, error) {
		var outbounds []json.RawMessage
		if err := json.Unmarshal(p.Outbounds, &outbounds); err != nil {
			return "", err
		}
		s := settings()
		opts, err := json.Marshal(libxray.BuildOptions{
			Outbounds:      outbounds,
			Mode:           s.Mode,
			DirectRules:    s.DirectSites,
			ProxyRules:     s.ProxySites,
			BlockRules:     s.BlockSites,
			DirectPrograms: directPrograms(s),
			ProxyPrograms:  s.ProxyPrograms,
			LogLevel:       "warning",
			LogFile:        logFile,
			Tun:            true,
			Windows:        true,
		})
		if err != nil {
			return "", err
		}
		return libxray.BuildConfig(string(opts))
	}
}
