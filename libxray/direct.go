package libxray

import (
	gonet "net"
	"net/http"
	"syscall"
	"time"

	"github.com/xtls/xray-core/transport/internet"
)

// Direct connections (downloads, certificate pins) leave the way Xray's own
// outbounds do: with the dialer controllers the app registered. Android and
// iOS register none, as the whole app is outside its tunnel there; the
// Windows service binds its sockets to the physical interface this way.

func directDialer(timeout time.Duration) *gonet.Dialer {
	return &gonet.Dialer{Timeout: timeout, Control: applyControllers}
}

func directListenConfig() *gonet.ListenConfig {
	return &gonet.ListenConfig{Control: applyControllers}
}

// directTransport is http.DefaultTransport dialing with the controllers.
func directTransport() *http.Transport {
	tr := http.DefaultTransport.(*http.Transport).Clone()
	d := directDialer(30 * time.Second)
	d.KeepAlive = 30 * time.Second
	tr.DialContext = d.DialContext
	return tr
}

func applyControllers(network, address string, c syscall.RawConn) error {
	for _, ctl := range internet.Controllers {
		if err := ctl(network, address, c); err != nil {
			return err
		}
	}
	return nil
}
