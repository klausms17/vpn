package service

import (
	"errors"
	"fmt"
	"strings"
	"syscall"
)

// The Windows errors the core's start reports most (winerror.h), as
// syscall.Errno, which is what x/sys/windows returns.
const (
	errObjectAlreadyExists = syscall.Errno(5010) // ERROR_OBJECT_ALREADY_EXISTS
	errNotFound            = syscall.Errno(1168) // ERROR_NOT_FOUND
)

// explainer turns an error of the core's start into what the window shows
// (engine.Deps.Explain). It looks at the PC only once the start failed.
type explainer struct {
	// holder names the network adapter, not the tunnel's, that holds the
	// tunnel's address, or "" if none does.
	holder func() string
	// ipv6Off tells whether IPv6 is switched off in Windows.
	ipv6Off func() bool
}

func (x explainer) explain(err error) (message string, final bool) {
	switch {
	case errors.Is(err, errObjectAlreadyExists):
		// Windows refuses an address that another adapter has.
		if name := x.holder(); name != "" {
			return fmt.Sprintf("Адрес VPN уже занят адаптером «%s», скорее всего другим VPN. Выключите его и подключитесь снова.", name), true
		}
		return "Адрес VPN был занят другим сетевым адаптером. Подключитесь ещё раз; если не выйдет, перезагрузите компьютер.", false
	case errors.Is(err, errNotFound):
		if x.ipv6Off() {
			return "В Windows выключен протокол IPv6, а без него адаптер Kirov VPN не настраивается. Включите IPv6 и перезагрузите компьютер.", true
		}
		return "Windows не успела подготовить сетевой адаптер Kirov VPN. Подключитесь ещё раз; если не выйдет, перезагрузите компьютер.", false
	case strings.Contains(err.Error(), "outside the TUN"):
		// The core's own words when it cannot add its WFP filters.
		return "Не удалось включить защиту от утечек DNS (фильтры Windows). Перезагрузите компьютер и подключитесь снова.", false
	}
	return fmt.Sprintf("Не удалось запустить VPN (%s). Подключитесь ещё раз; если не выйдет, перезагрузите компьютер.", innermost(err)), false
}

// innermost is the text of the error at the bottom of err's chain: the
// cause, without the core's wrapping.
func innermost(err error) string {
	for {
		next := errors.Unwrap(err)
		if next == nil {
			return strings.TrimSuffix(err.Error(), ".")
		}
		err = next
	}
}
