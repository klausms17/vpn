// Package libxray is a small gomobile-friendly wrapper around Xray-core.
//
// The Android app talks to Xray only through this package. Every exported
// symbol here uses types that gomobile can bind (string, int32/int64, bool,
// []byte, error, pointers to exported structs).
package libxray

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/geodata"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Registers every protocol, transport and app that Xray ships with.
	_ "github.com/xtls/xray-core/main/distro/all"
)

const (
	envAsset = "xray.location.asset"
	envCert  = "xray.location.cert"
	envTunFd = "xray.tun.fd"

	// Outbound tag the app uses for the proxy server. Delay tests are forced
	// through it so they never silently measure the direct route.
	ProxyTag = "proxy"
	// Outbound tag the app uses for direct (bypassed) traffic.
	DirectTag = "direct"
)

// InitEnv tells Xray where geoip.dat / geosite.dat live. Call it once per
// process before starting anything.
func InitEnv(assetDir string) {
	_ = os.Setenv(envAsset, assetDir)
	_ = os.Setenv(envCert, assetDir)
}

// Version returns the Xray-core version compiled into the library.
func Version() string {
	return core.Version()
}

// Controller owns a single running Xray instance bound to the VPN TUN fd.
type Controller struct {
	mu       sync.Mutex
	instance *core.Instance
	stats    stats.Manager
}

// NewController creates an idle controller.
func NewController() *Controller {
	return &Controller{}
}

// Start builds an Xray instance from configJSON and starts it. tunFd is the
// file descriptor of the Android VPN interface (0 when the config has no tun
// inbound). The fd stays owned by the caller: Xray never closes it, so the
// caller must close it only after Stop returns.
func (c *Controller) Start(configJSON string, tunFd int32) (err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	defer recoverInto(&err)

	if c.instance != nil {
		return errors.New("core is already running")
	}
	if err := os.Setenv(envTunFd, strconv.Itoa(int(tunFd))); err != nil {
		return fmt.Errorf("set tun fd: %w", err)
	}
	if err := reloadGeoIfChanged(); err != nil {
		return err
	}

	inst, err := newInstance(configJSON)
	if err != nil {
		return err
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return fmt.Errorf("start failed: %w", err)
	}

	c.instance = inst
	if sm, ok := inst.GetFeature(stats.ManagerType()).(stats.Manager); ok {
		c.stats = sm
	}
	// Parsing geo files and configs leaves a lot of garbage behind; hand it
	// back to the OS so the long-lived VPN process stays small.
	go releaseMemory()
	return nil
}

// Stop shuts the running instance down. It is safe to call when not running.
func (c *Controller) Stop() (err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	defer recoverInto(&err)

	if c.instance == nil {
		return nil
	}
	err = c.instance.Close()
	c.instance = nil
	c.stats = nil
	go releaseMemory()
	return err
}

// IsRunning reports whether an instance is currently running.
func (c *Controller) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.instance != nil
}

// Traffic holds byte counters accumulated since the previous QueryTraffic call.
type Traffic struct {
	ProxyUp    int64
	ProxyDown  int64
	DirectUp   int64
	DirectDown int64
}

// QueryTraffic returns and resets the proxy/direct outbound traffic counters.
// The config must enable "stats" and the outbound uplink/downlink policy.
func (c *Controller) QueryTraffic() *Traffic {
	c.mu.Lock()
	sm := c.stats
	c.mu.Unlock()

	t := &Traffic{}
	if sm == nil {
		return t
	}
	take := func(tag, dir string) int64 {
		if counter := sm.GetCounter("outbound>>>" + tag + ">>>traffic>>>" + dir); counter != nil {
			return counter.Set(0)
		}
		return 0
	}
	t.ProxyUp = take(ProxyTag, "uplink")
	t.ProxyDown = take(ProxyTag, "downlink")
	t.DirectUp = take(DirectTag, "uplink")
	t.DirectDown = take(DirectTag, "downlink")
	return t
}

// MeasureDelay performs an HTTP GET to url through the proxy outbound of the
// running instance and returns the round-trip time in milliseconds.
func (c *Controller) MeasureDelay(url string, timeoutMs int32) (ms int64, err error) {
	defer recoverInto(&err)

	c.mu.Lock()
	inst := c.instance
	c.mu.Unlock()
	if inst == nil {
		return -1, errors.New("core is not running")
	}
	return measureDelay(inst, url, timeoutDuration(timeoutMs))
}

// MeasureOutboundDelay starts a temporary instance from configJSON (which
// must contain an outbound tagged "proxy" and no inbounds), measures the
// delay through it and shuts it down. Used to test servers while the VPN is
// off or connected to another server.
func MeasureOutboundDelay(configJSON string, url string, timeoutMs int32) (ms int64, err error) {
	defer recoverInto(&err)

	inst, err := newInstance(configJSON)
	if err != nil {
		return -1, err
	}
	defer inst.Close()
	if err := inst.Start(); err != nil {
		return -1, fmt.Errorf("start failed: %w", err)
	}
	return measureDelay(inst, url, timeoutDuration(timeoutMs))
}

// ValidateConfig checks that configJSON parses and that every referenced
// resource (geo codes, keys, transports) is valid, without starting it.
func ValidateConfig(configJSON string) (err error) {
	defer recoverInto(&err)

	inst, err := newInstance(configJSON)
	if err != nil {
		return err
	}
	return inst.Close()
}

func newInstance(configJSON string) (*core.Instance, error) {
	config, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		return nil, fmt.Errorf("config error: %w", err)
	}
	inst, err := core.New(config)
	if err != nil {
		return nil, fmt.Errorf("core init failed: %w", err)
	}
	return inst, nil
}

// dialThroughProxy opens a connection to addr ("host:port") via the proxy
// outbound of inst, bypassing routing rules.
func dialThroughProxy(ctx context.Context, inst *core.Instance, network, addr string) (net.Conn, error) {
	dest, err := net.ParseDestination(network + ":" + addr)
	if err != nil {
		return nil, err
	}
	ctx = session.SetForcedOutboundTagToContext(ctx, ProxyTag)
	return core.Dial(ctx, inst, dest)
}

func timeoutDuration(ms int32) time.Duration {
	if ms <= 0 {
		return 10 * time.Second
	}
	return time.Duration(ms) * time.Millisecond
}

func releaseMemory() {
	runtime.GC()
	debug.FreeOSMemory()
}

// recoverInto converts a panic inside Xray into an ordinary error so a bad
// config can never take the whole VPN process down during start/stop.
func recoverInto(err *error) {
	if r := recover(); r != nil {
		*err = fmt.Errorf("xray panic: %v", r)
	}
}

// Xray caches geoip/geosite matchers per file name for the life of the
// process, so after the app updates the databases a restarted core would
// keep routing with the old ones. Reload them when the files changed.
var geoStamp struct {
	sync.Mutex
	value string
}

func reloadGeoIfChanged() error {
	dir := os.Getenv(envAsset)
	if dir == "" {
		return nil
	}
	var b strings.Builder
	for _, name := range []string{"geoip.dat", "geosite.dat"} {
		fi, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			return nil // the core reports a missing file itself
		}
		fmt.Fprintf(&b, "%s:%d:%d;", name, fi.Size(), fi.ModTime().UnixNano())
	}
	cur := b.String()
	geoStamp.Lock()
	defer geoStamp.Unlock()
	if geoStamp.value == "" || geoStamp.value == cur {
		// First core in this process (nothing cached yet), or unchanged.
		geoStamp.value = cur
		return nil
	}
	if err := errors.Join(geodata.IPReg.Reload(), geodata.DomainReg.Reload()); err != nil {
		return fmt.Errorf("reload geo data: %w", err)
	}
	geoStamp.value = cur
	return nil
}
