package libxray

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	gonet "net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/klausms17/vpn/libxray/internal/fsx"
	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
)

// geoip.dat and geosite.dat are both protobuf lists shaped like
//
//	repeated Entry entry = 1;   // Entry starts with: string code = 1;
//
// The helpers below stream through them entry by entry, so even the 70+ MB
// upstream geosite.dat never has to fit in memory on a phone.

type geoEntry struct {
	code   string // upper-case
	header []byte // tag + length varint of the entry
	body   []byte // only filled when the visitor asked for it
}

// walkGeo calls visit for every entry. visit returns whether it needs the
// entry body; bodies that are not needed are skipped without allocation.
func walkGeo(r io.Reader, visit func(code string) (needBody bool), onBody func(e *geoEntry) error) (int, error) {
	br := bufio.NewReaderSize(r, 256<<10)
	entries := 0
	for {
		tag, err := br.ReadByte()
		if err == io.EOF {
			return entries, nil
		}
		if err != nil {
			return entries, err
		}
		if tag != 0x0a { // field 1, length-delimited
			return entries, fmt.Errorf("unexpected tag 0x%02x", tag)
		}
		size, err := readVarint(br)
		if err != nil {
			return entries, err
		}
		if size < 2 || size > 1<<30 {
			return entries, errors.New("invalid entry size")
		}
		// Peek the code without consuming the body.
		peekLen := 2 + 256
		if uint64(peekLen) > size {
			peekLen = int(size)
		}
		head, err := br.Peek(peekLen)
		if err != nil {
			return entries, err
		}
		if head[0] != 0x0a {
			return entries, fmt.Errorf("unexpected entry tag 0x%02x", head[0])
		}
		codeLen, n := decodeVarint(head[1:])
		if n == 0 || codeLen > 256 || uint64(1+n)+codeLen > size || 1+n+int(codeLen) > len(head) {
			return entries, errors.New("invalid code length")
		}
		code := strings.ToUpper(string(head[1+n : 1+n+int(codeLen)]))
		entries++

		if !visit(code) {
			if _, err := br.Discard(int(size)); err != nil {
				return entries, err
			}
			continue
		}
		body := make([]byte, size)
		if _, err := io.ReadFull(br, body); err != nil {
			return entries, err
		}
		header := append([]byte{tag}, encodeVarint(size)...)
		if err := onBody(&geoEntry{code: code, header: header, body: body}); err != nil {
			return entries, err
		}
	}
}

func parseCodes(codes string) map[string]bool {
	want := map[string]bool{}
	for _, c := range strings.Split(codes, ",") {
		if c = strings.ToUpper(strings.TrimSpace(c)); c != "" {
			want[c] = false
		}
	}
	return want
}

func missingCodes(want map[string]bool) error {
	var missing []string
	for code, found := range want {
		if !found {
			missing = append(missing, strings.ToLower(code))
		}
	}
	if len(missing) > 0 {
		return fmt.Errorf("missing categories: %s", strings.Join(missing, ", "))
	}
	return nil
}

// CheckGeoFile verifies that the geo file at path is well formed and holds
// every code from the comma separated list. Used on downloaded files before
// they replace the working ones, so a broken download can't break routing.
func CheckGeoFile(path string, codes string) (err error) {
	defer recoverInto(&err)

	want := parseCodes(codes)
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()

	entries, err := walkGeo(f, func(code string) bool {
		if _, ok := want[code]; ok {
			want[code] = true
		}
		return false
	}, nil)
	if err != nil {
		return fmt.Errorf("%s is corrupted: %w", filepath.Base(path), err)
	}
	if entries == 0 {
		return fmt.Errorf("%s is empty", filepath.Base(path))
	}
	return missingCodes(want)
}

// TrimGeoFile writes to dst only the entries of src listed in codes. The
// result is what the VPN actually loads: a few MB instead of ~90 MB, which
// keeps start-up fast and memory low. dst is replaced atomically and only
// if every requested code was found.
func TrimGeoFile(src, dst, codes string) (err error) {
	defer recoverInto(&err)

	want := parseCodes(codes)
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	tmp := dst + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	defer func() {
		if out != nil {
			out.Close()
			os.Remove(tmp)
		}
	}()
	w := bufio.NewWriterSize(out, 256<<10)

	_, err = walkGeo(in, func(code string) bool {
		found, ok := want[code]
		return ok && !found // keep the first occurrence only
	}, func(e *geoEntry) error {
		want[e.code] = true
		if _, err := w.Write(e.header); err != nil {
			return err
		}
		_, err := w.Write(e.body)
		return err
	})
	if err != nil {
		return fmt.Errorf("%s is corrupted: %w", filepath.Base(src), err)
	}
	if err := missingCodes(want); err != nil {
		return err
	}
	if err := w.Flush(); err != nil {
		return err
	}
	if err := out.Sync(); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		out = nil
		os.Remove(tmp)
		return err
	}
	out = nil
	return fsx.Replace(tmp, dst)
}

// GeoIPContains reports whether ip (IPv4 or IPv6 literal) belongs to the
// given category of a geoip.dat file, e.g. code "ru-whitelist".
func GeoIPContains(path, code, ip string) (ok bool, err error) {
	defer recoverInto(&err)

	addr, err := netip.ParseAddr(strings.TrimSpace(ip))
	if err != nil {
		return false, err
	}
	set, err := loadGeoIPSet(path, code)
	if err != nil {
		return false, err
	}
	return set.contains(addr), nil
}

// geoIPSet is one parsed geoip category. It never changes once built.
type geoIPSet struct {
	key      string // see loadGeoIPSet
	prefixes []netip.Prefix
	reverse  bool
}

func (s *geoIPSet) contains(addr netip.Addr) bool {
	addr = addr.Unmap()
	for _, p := range s.prefixes {
		if p.Contains(addr) {
			return !s.reverse
		}
	}
	return s.reverse
}

// geoIPCache holds the category parsed last. HostInGeoIP checks every
// address of every server against the same category, and parsing
// ru-whitelist takes milliseconds and megabytes each time. Dropped after
// geoIPCacheIdle unused, so the VPN process does not keep it after one
// failover.
var geoIPCache struct {
	sync.Mutex
	set   *geoIPSet
	timer *time.Timer
}

const geoIPCacheIdle = time.Minute

// loadGeoIPSet parses the category once for as long as the file stays the
// same. The key comes from the opened file: the app replaces geoip.dat by
// rename, and a stat of the path before the open could describe the old
// file while the open reads the new one.
func loadGeoIPSet(path, code string) (*geoIPSet, error) {
	code = strings.ToUpper(code)
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return nil, err
	}
	key := fmt.Sprintf("%s|%s|%d|%d", path, code, fi.Size(), fi.ModTime().UnixNano())

	// Held while parsing, so callers in parallel parse only once.
	geoIPCache.Lock()
	defer geoIPCache.Unlock()
	if geoIPCache.timer == nil {
		geoIPCache.timer = time.AfterFunc(geoIPCacheIdle, dropGeoIPCache)
	} else {
		geoIPCache.timer.Reset(geoIPCacheIdle)
	}
	if s := geoIPCache.set; s != nil && s.key == key {
		return s, nil
	}
	s, err := readGeoIPSet(f, code)
	if err != nil {
		return nil, err
	}
	s.key = key
	geoIPCache.set = s
	return s, nil
}

func dropGeoIPCache() {
	geoIPCache.Lock()
	defer geoIPCache.Unlock()
	geoIPCache.set = nil
}

// readGeoIPSet parses the category code (upper-case) of a geoip.dat.
func readGeoIPSet(r io.Reader, code string) (*geoIPSet, error) {
	var entry *geodata.GeoIP
	_, err := walkGeo(r, func(c string) bool {
		return entry == nil && c == code
	}, func(e *geoEntry) error {
		entry = new(geodata.GeoIP)
		return proto.Unmarshal(e.body, entry)
	})
	if err != nil {
		return nil, err
	}
	if entry == nil {
		return nil, fmt.Errorf("no category %s", strings.ToLower(code))
	}
	s := &geoIPSet{prefixes: make([]netip.Prefix, 0, len(entry.Cidr)), reverse: entry.ReverseMatch}
	for _, cidr := range entry.Cidr {
		ip, ok := netip.AddrFromSlice(cidr.Ip)
		if !ok {
			continue
		}
		if p, err := ip.Unmap().Prefix(int(cidr.Prefix)); err == nil {
			s.prefixes = append(s.prefixes, p)
		}
	}
	return s, nil
}

func readVarint(r *bufio.Reader) (uint64, error) {
	var x uint64
	for shift := uint(0); shift < 64; shift += 7 {
		b, err := r.ReadByte()
		if err != nil {
			if err == io.EOF {
				return 0, io.ErrUnexpectedEOF
			}
			return 0, err
		}
		x |= uint64(b&0x7f) << shift
		if b&0x80 == 0 {
			return x, nil
		}
	}
	return 0, errors.New("varint overflow")
}

// decodeVarint returns the value and the number of bytes used (0 on error).
func decodeVarint(b []byte) (uint64, int) {
	var x uint64
	for i := 0; i < len(b) && i < 10; i++ {
		x |= uint64(b[i]&0x7f) << (7 * uint(i))
		if b[i]&0x80 == 0 {
			return x, i + 1
		}
	}
	return 0, 0
}

func encodeVarint(v uint64) []byte {
	var out []byte
	for v >= 0x80 {
		out = append(out, byte(v)|0x80)
		v >>= 7
	}
	return append(out, byte(v))
}

// HostInGeoIP resolves host (unless it already is an IP) and reports
// whether all of its addresses fall into the geoip category, e.g.
// "ru-whitelist" to tell if a server stays reachable when mobile internet
// runs in whitelist-only mode. Returns 1 (yes), 0 (no) or -1 (mixed).
// (The result must not be named "res": gomobile's Objective-C code uses it.)
func HostInGeoIP(path, code, host string, timeoutMs int32) (verdict int32, err error) {
	defer recoverInto(&err)

	var ips []string
	if addr, perr := netip.ParseAddr(strings.Trim(host, "[]")); perr == nil {
		ips = []string{addr.String()}
	} else {
		ctx, cancel := context.WithTimeout(context.Background(), timeoutDuration(timeoutMs))
		defer cancel()
		addrs, lerr := gonet.DefaultResolver.LookupHost(ctx, host)
		if lerr != nil {
			return 0, lerr
		}
		ips = addrs
	}
	set, err := loadGeoIPSet(path, code)
	if err != nil {
		return 0, err
	}
	in, out := 0, 0
	for _, ip := range ips {
		addr, err := netip.ParseAddr(ip)
		if err != nil {
			return 0, err
		}
		if set.contains(addr) {
			in++
		} else {
			out++
		}
	}
	switch {
	case out == 0 && in > 0:
		return 1, nil
	case in == 0:
		return 0, nil
	default:
		return -1, nil
	}
}
