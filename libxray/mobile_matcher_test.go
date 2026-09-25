package libxray

import (
	"os"
	"reflect"
	"testing"
	"unsafe"

	"github.com/xtls/xray-core/common/geodata"
	"github.com/xtls/xray-core/common/geodata/strmatcher"
	"github.com/xtls/xray-core/common/utils"
)

// TestMain switches Xray's domain matcher to the implementation it uses on
// Android/iOS (it picks by runtime.GOOS), so memory/speed numbers and
// routing results reflect the phone. Set DESKTOP_MATCHER=1 to skip.
func TestMain(m *testing.M) {
	if os.Getenv("DESKTOP_MATCHER") == "" {
		f := &geodata.CompactDomainMatcherFactory{}
		setField(f, "shared", utils.NewWeakCacheMap[string, strmatcher.LinearAnyMatcher]())
		setField(geodata.DomainReg, "factory", geodata.DomainMatcherFactory(f))
	}
	os.Exit(m.Run())
}

func setField(obj any, name string, value any) {
	f := reflect.ValueOf(obj).Elem().FieldByName(name)
	reflect.NewAt(f.Type(), unsafe.Pointer(f.UnsafeAddr())).Elem().Set(reflect.ValueOf(value))
}
