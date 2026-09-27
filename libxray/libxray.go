// Package libxray is a small gomobile-friendly wrapper around Xray-core.
//
// The Android app talks to Xray only through this package. Every exported
// symbol here uses types that gomobile can bind (string, int32/int64, bool,
// []byte, error, pointers to exported structs).
package libxray

import (
	"context"
	"encoding/json"
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

	applog "github.com/xtls/xray-core/app/log"
	"github.com/xtls/xray-core/common/geodata"
	commonlog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/dns"
	xoutbound "github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"

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

// crashLog stays open for the life of the process: the runtime writes a
// fatal error there as it dies.
var crashLog struct {
	sync.Mutex
	file *os.File
}

// SetCrashLog makes a Go panic or fatal error that ends the process also
// write its report to path (appended; kept to the last ~256 KB), so a VPN
// that stopped by itself leaves a trace the user can send. Call once per
// process.
func SetCrashLog(path string) error {
	crashLog.Lock()
	defer crashLog.Unlock()
	if crashLog.file != nil {
		return nil
	}
	if st, err := os.Stat(path); err == nil && st.Size() > 256<<10 {
		_ = os.Rename(path, path+".1")
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	if err := debug.SetCrashOutput(f, debug.CrashOptions{}); err != nil {
		f.Close()
		return err
	}
	crashLog.file = f
	return nil
}

// Version returns the Xray-core version compiled into the library.
func Version() string {
	return core.Version()
}

// Controller owns a single running Xray instance bound to the VPN TUN fd.
type Controller struct {
	mu  sync.Mutex
	cur *run // nil when not running
}

// run is one started instance and the calls that use it (delay tests,
// fetches through the tunnel). Stop cancels those calls and waits for them
// before closing the instance, so nothing works on a closing core.
type run struct {
	inst   *core.Instance
	stats  stats.Manager
	ctx    context.Context
	cancel context.CancelFunc
	calls  sync.WaitGroup
}

// stopWait bounds how long Stop waits for cancelled calls to return.
const stopWait = 3 * time.Second

// use hands out the running instance for one call; done must be called
// when the call is over. ctx ends when the instance is stopped.
func (c *Controller) use() (inst *core.Instance, ctx context.Context, done func(), err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	r := c.cur
	if r == nil {
		return nil, nil, nil, errors.New("core is not running")
	}
	r.calls.Add(1)
	return r.inst, r.ctx, r.calls.Done, nil
}

// NewController creates an idle controller.
func NewController() *Controller {
	return &Controller{}
}

// Start builds an Xray instance from configJSON and starts it. tunFd is the
// file descriptor of the VPN interface: Android's VpnService fd, or the
// utun of an iOS packet tunnel (TunnelFD); 0 when the config has no tun
// inbound. The fd stays owned by the caller: Xray never closes it, so the
// caller must close it only after Stop returns (on iOS Xray works on a
// copy, see prepareTunFd).
func (c *Controller) Start(configJSON string, tunFd int32) (err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	defer recoverInto(&err)

	if c.cur != nil {
		return errors.New("core is already running")
	}
	fd, err := prepareTunFd(tunFd)
	if err != nil {
		return fmt.Errorf("tun fd: %w", err)
	}
	if fd > 0 {
		err = os.Setenv(envTunFd, strconv.Itoa(int(fd)))
	} else {
		err = os.Unsetenv(envTunFd)
	}
	if err != nil {
		return fmt.Errorf("set tun fd: %w", err)
	}
	defer startGC()()
	reloadGeoIfChanged()

	inst, err := newInstance(configJSON)
	if err != nil {
		return err
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return fmt.Errorf("start failed: %w", err)
	}

	r := &run{inst: inst}
	r.ctx, r.cancel = context.WithCancel(context.Background())
	if sm, ok := inst.GetFeature(stats.ManagerType()).(stats.Manager); ok {
		r.stats = sm
	}
	c.cur = r
	// Parsing geo files and configs leaves a lot of garbage behind; hand it
	// back to the OS so the long-lived VPN process stays small.
	go releaseMemory()
	return nil
}

// Stop shuts the running instance down. It is safe to call when not running.
//
// Known limitation: UDP flows (QUIC, calls) outlive it. Xray keeps them
// outside the TUN stack, on a context the instance cannot cancel, until
// their idle timer ends them (5 to 10 awake minutes direct, 15 to 30
// through the proxy; see the policy levels in buildConfig), or later while
// the far end keeps sending, and until then they keep the old instance in
// memory. Ending them here needs a change inside Xray.
func (c *Controller) Stop() (err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	defer recoverInto(&err)

	r := c.cur
	if r == nil {
		return nil
	}
	// Detached first: even if closing panics, the next Start works.
	c.cur = nil
	r.cancel()
	waitFor(&r.calls, stopWait)
	err = r.inst.Close()
	go releaseMemory()
	return err
}

// waitFor waits for wg, at most d. No Add can follow: the run is detached.
func waitFor(wg *sync.WaitGroup, d time.Duration) {
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(d):
	}
}

// IsRunning reports whether an instance is currently running.
func (c *Controller) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.cur != nil
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
	var sm stats.Manager
	c.mu.Lock()
	if c.cur != nil {
		sm = c.cur.stats
	}
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

	inst, ctx, done, err := c.use()
	if err != nil {
		return -1, err
	}
	defer done()
	return measureDelay(ctx, inst, url, timeoutDuration(timeoutMs))
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
	return measureDelay(context.Background(), inst, url, timeoutDuration(timeoutMs))
}

// FetchThroughTunnel is FetchWithHeaders through the proxy outbound of the
// running instance: for refreshing a subscription whose panel is blocked
// while the VPN is up, without a temporary core. Fails when not running.
func (c *Controller) FetchThroughTunnel(url, userAgent, headersJSON string, timeoutMs int32) (result *FetchResult, err error) {
	defer recoverInto(&err)

	extra, err := parseHeaders(headersJSON)
	if err != nil {
		return nil, err
	}
	inst, ctx, done, err := c.use()
	if err != nil {
		return nil, err
	}
	defer done()
	tr := proxyTransport(inst)
	defer tr.CloseIdleConnections()
	return doFetch(ctx, tr, url, userAgent, extra, timeoutDuration(timeoutMs))
}

// maxProbeParallel caps ProbeOutbounds' concurrent probes: each one holds
// the buffers of a proxy handshake.
const maxProbeParallel = 6

// ProbeOutbounds measures the delay to url through several candidate
// servers at once. candidatesJSON is a JSON array with one entry per
// candidate: the profile's outbounds array (as for BuildProxyOnlyConfig).
// It returns a JSON array of int64 in input order: the delay in ms, or -1
// when the candidate failed or is invalid.
//
// All candidates share one temporary proxy-only instance, their tags moved
// apart ("c<N>-..."). Creating an instance takes over Xray's process-wide
// state; the running tunnel gets it back right away (see restoreGlobals),
// so its xray.log and chained servers keep working. At most parallel
// (1..6) probes run at a time, each limited by timeoutMs. Works whether or
// not the controller is running.
func (c *Controller) ProbeOutbounds(candidatesJSON string, url string, timeoutMs int32, parallel int32) (resultJSON string, err error) {
	defer recoverInto(&err)

	var candidates []json.RawMessage
	if err := json.Unmarshal([]byte(candidatesJSON), &candidates); err != nil {
		return "", fmt.Errorf("bad candidates: %w", err)
	}
	results := make([]int64, len(candidates))
	roots := make([]string, len(candidates)) // "" = invalid candidate
	groups := make([][]any, len(candidates))
	for i, raw := range candidates {
		results[i] = -1
		obs, root, err := probeCandidate(raw, "c"+strconv.Itoa(i))
		if err != nil {
			continue
		}
		groups[i], roots[i] = obs, root
	}
	defer func() {
		// Handshakes and the instance leave megabytes of garbage; the VPN
		// process must stay small.
		go releaseMemory()
	}()
	// After the probe instance is closed (deferred below): the tunnel's
	// state again, without the probe's outbounds.
	defer c.restoreGlobals(nil)

	inst, err := c.newProbeInstance(groups)
	if err != nil {
		// Every candidate passed its own checks, yet the core refused them
		// together: find the culprits one by one.
		for i := range groups {
			if groups[i] == nil {
				continue
			}
			one, err := c.newProbeInstance([][]any{groups[i]})
			if err != nil {
				groups[i], roots[i] = nil, ""
			} else if one != nil {
				_ = one.Close()
			}
		}
		if inst, err = c.newProbeInstance(groups); err != nil {
			return "", err
		}
	}
	if inst != nil {
		defer inst.Close()
		if err := inst.Start(); err != nil {
			return "", fmt.Errorf("start failed: %w", err)
		}
		timeout := timeoutDuration(timeoutMs)
		sem := make(chan struct{}, min(max(int(parallel), 1), maxProbeParallel))
		var wg sync.WaitGroup
		for i, tag := range roots {
			if tag == "" {
				continue
			}
			wg.Add(1)
			go func() {
				defer wg.Done()
				// recoverInto only covers the calling goroutine.
				defer func() { _ = recover() }()
				sem <- struct{}{}
				defer func() { <-sem }()
				if ms, err := measureDelayVia(context.Background(), inst, tag, url, timeout); err == nil {
					results[i] = ms
				}
			}()
		}
		wg.Wait()
	}
	out, err := json.Marshal(results)
	return string(out), err
}

// probeCandidate namespaces one candidate's outbounds and checks each of
// them the way the core will, so a single bad candidate, even one that
// panics a parser, is reported as -1 instead of failing the whole batch.
func probeCandidate(raw json.RawMessage, prefix string) (obs []any, root string, err error) {
	defer recoverInto(&err)

	var outbounds []json.RawMessage
	if err := json.Unmarshal(raw, &outbounds); err != nil {
		return nil, "", err
	}
	if obs, root, err = namespaceOutbounds(outbounds, prefix); err != nil {
		return nil, "", err
	}
	for _, ob := range obs {
		var detour conf.OutboundDetourConfig
		if err := json.Unmarshal(mustJSON(ob), &detour); err != nil {
			return nil, "", err
		}
		if _, err := detour.Build(); err != nil {
			return nil, "", err
		}
	}
	return obs, root, nil
}

// newProbeInstance builds (but does not start) a silent proxy-only
// instance from the given outbound groups, skipping nil ones. It returns
// nil, nil when there is nothing to build.
func (c *Controller) newProbeInstance(groups [][]any) (*core.Instance, error) {
	var all []any
	for _, g := range groups {
		all = append(all, g...)
	}
	if len(all) == 0 {
		return nil, nil
	}
	cfg, err := json.Marshal(map[string]any{
		"log":       map[string]any{"loglevel": "none"},
		"outbounds": all,
	})
	if err != nil {
		return nil, err
	}
	inst, err := newInstance(string(cfg))
	// Even a failed core.New may have taken the globals over already.
	c.restoreGlobals(inst)
	return inst, err
}

// restoreGlobals gives the running instance back the process-wide state
// that every new instance takes over on creation: Xray's log handler
// (app/log.New), else the tunnel's xray.log stays silent once the other
// instance is closed, and the system dialer's DNS client and outbound
// manager (core.New -> internet.InitSystemDialer), which resolve every
// sockopt.dialerProxy: a chained tunnel would dial its hop in the other
// instance. While probe is not nil its outbounds stay reachable as hops too.
func (c *Controller) restoreGlobals(probe *core.Instance) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cur == nil {
		return
	}
	running := c.cur.inst
	if lg, ok := running.GetFeature((*applog.Instance)(nil)).(*applog.Instance); ok {
		commonlog.RegisterHandler(lg)
	}
	dc, _ := running.GetFeature(dns.ClientType()).(dns.Client)
	om, _ := running.GetFeature(xoutbound.ManagerType()).(xoutbound.Manager)
	if dc == nil || om == nil {
		return
	}
	if probe != nil {
		if pom, ok := probe.GetFeature(xoutbound.ManagerType()).(xoutbound.Manager); ok {
			om = &sharedOutbounds{Manager: om, probe: pom}
		}
	}
	internet.InitSystemDialer(dc, om)
}

// sharedOutbounds resolves dialerProxy tags in the tunnel first, then in the
// probe instance (their tags never collide: the probe's are "c<N>-...").
type sharedOutbounds struct {
	xoutbound.Manager
	probe xoutbound.Manager
}

func (m *sharedOutbounds) GetHandler(tag string) xoutbound.Handler {
	if h := m.Manager.GetHandler(tag); h != nil {
		return h
	}
	return m.probe.GetHandler(tag)
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

// dialVia opens a connection to addr ("host:port") through the outbound
// tagged tag of inst, bypassing routing rules.
func dialVia(ctx context.Context, inst *core.Instance, tag, network, addr string) (net.Conn, error) {
	dest, err := net.ParseDestination(network + ":" + addr)
	if err != nil {
		return nil, err
	}
	ctx = session.SetForcedOutboundTagToContext(ctx, tag)
	return core.Dial(ctx, inst, dest)
}

func timeoutDuration(ms int32) time.Duration {
	if ms <= 0 {
		return 10 * time.Second
	}
	return time.Duration(ms) * time.Millisecond
}

// ReleaseMemory hands freed memory back to the system at once, e.g. when
// iOS warns the tunnel extension about memory.
func ReleaseMemory() { releaseMemory() }

func releaseMemory() {
	runtime.GC()
	debug.FreeOSMemory()
}

// recoverInto turns a panic into an ordinary error. gomobile does not
// recover panics, so one inside Xray or in a parser fed with a panel's
// answer would take the whole app or VPN process down. Exported functions
// that parse input or run Xray defer it.
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

// reloadGeo is replaced in tests.
var reloadGeo = func() error { return errors.Join(geodata.IPReg.Reload(), geodata.DomainReg.Reload()) }

// reloadGeoIfChanged never fails the start: if the reload does not work,
// the core simply keeps the lists it already had, as before the update.
func reloadGeoIfChanged() {
	dir := os.Getenv(envAsset)
	if dir == "" {
		return
	}
	var b strings.Builder
	for _, name := range []string{"geoip.dat", "geosite.dat"} {
		fi, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			return // the core reports a missing file itself
		}
		fmt.Fprintf(&b, "%s:%d:%d;", name, fi.Size(), fi.ModTime().UnixNano())
	}
	cur := b.String()
	geoStamp.Lock()
	defer geoStamp.Unlock()
	if geoStamp.value == "" || geoStamp.value == cur {
		// First core in this process (nothing cached yet), or unchanged.
		geoStamp.value = cur
		return
	}
	if err := reloadGeo(); err != nil {
		return // keep the stamp: try again on the next start
	}
	geoStamp.value = cur
}
