package libxray

import (
	xlog "github.com/xtls/xray-core/app/log"
	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/log"

	"github.com/klausms17/vpn/libxray/internal/redact"
)

// The core's log file names no site and no address: its warnings and
// errors quote the names it failed to resolve or reach, the server's
// among them, and the file stays on a device that may be searched.
// Xray's own masking would cover IP addresses only.
func init() {
	common.Must(xlog.RegisterHandlerCreator(xlog.LogType_File, redactedFileLog))
}

func redactedFileLog(_ xlog.LogType, o xlog.HandlerCreatorOptions) (log.Handler, error) {
	create, err := log.CreateFileLogWriter(o.Path)
	if err != nil {
		return nil, err
	}
	return log.NewLogger(func() log.Writer {
		w := create()
		if w == nil {
			return nil
		}
		return redacted{w}
	}), nil
}

type redacted struct{ log.Writer }

func (w redacted) Write(s string) error { return w.Writer.Write(redact.Hosts(redact.IPs(s))) }
