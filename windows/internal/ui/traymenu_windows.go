package ui

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

// trayMenu shows the tray icon's menu itself. Wails v3.0.0-beta.26 never
// opens a tray menu on Windows (wailsapp/wails#6161): it registers the
// icon with NOTIFYICON_VERSION_4, where a right-click arrives as
// WM_CONTEXTMENU, which it does not handle, and an icon in Windows 11's
// overflow area has no bounds to show a menu at. Drop this once a Wails
// release carries the fix (wailsapp/wails#6162).
type trayMenu struct {
	// open is what a click on the icon does.
	open func()
	// items gives the menu as it is now; a nil item is a separator.
	items func() []*menuItem
}

type menuItem struct {
	label   string
	enabled bool
	action  func()
}

var (
	user32                  = windows.NewLazySystemDLL("user32.dll")
	procCreatePopupMenu     = user32.NewProc("CreatePopupMenu")
	procAppendMenuW         = user32.NewProc("AppendMenuW")
	procTrackPopupMenu      = user32.NewProc("TrackPopupMenu")
	procDestroyMenu         = user32.NewProc("DestroyMenu")
	procSetForegroundWindow = user32.NewProc("SetForegroundWindow")
	procPostMessageW        = user32.NewProc("PostMessageW")
)

const (
	// wailsTrayMessage is Wails' wmUserSystray (WM_USER + 1), the
	// message its tray icon reports events with.
	wailsTrayMessage = 0x0401
	wmContextMenu    = 0x007B
	wmNull           = 0x0000
	ninSelect        = 0x0400
	ninKeySelect     = 0x0401
	mfGrayed         = 0x0001
	mfSeparator      = 0x0800
	tpmRightButton   = 0x0002
	tpmNoNotify      = 0x0080
	tpmReturnCmd     = 0x0100
)

// intercept is a Wails WndProcInterceptor: it takes the tray icon's
// menu and selection events before Wails does.
func (t *trayMenu) intercept(hwnd uintptr, msg uint32, wParam, lParam uintptr) (uintptr, bool) {
	if msg != wailsTrayMessage {
		return 0, false
	}
	switch lParam & 0xffff {
	case wmContextMenu:
		// NOTIFYICON_VERSION_4 passes where the menu belongs in wParam.
		t.show(hwnd, int32(int16(wParam&0xffff)), int32(int16(wParam>>16&0xffff)))
		return 0, true
	case ninSelect, ninKeySelect:
		go t.open()
		return 0, true
	}
	return 0, false
}

// show runs the menu at x, y on the thread of hwnd, the tray icon's window.
func (t *trayMenu) show(hwnd uintptr, x, y int32) {
	menu, _, _ := procCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	defer procDestroyMenu.Call(menu)
	items := t.items()
	for i, item := range items {
		if item == nil {
			procAppendMenuW.Call(menu, mfSeparator, 0, 0)
			continue
		}
		var flags uintptr
		if !item.enabled {
			flags |= mfGrayed
		}
		label, err := windows.UTF16PtrFromString(item.label)
		if err != nil {
			return
		}
		procAppendMenuW.Call(menu, flags, uintptr(i+1), uintptr(unsafe.Pointer(label)))
	}
	// Without the foreground the menu stays open when the user clicks
	// elsewhere; the empty message after it is Microsoft's advice
	// (KB135788) for the next time it opens.
	procSetForegroundWindow.Call(hwnd)
	cmd, _, _ := procTrackPopupMenu.Call(menu, tpmRightButton|tpmNoNotify|tpmReturnCmd, uintptr(x), uintptr(y), 0, hwnd, 0)
	procPostMessageW.Call(hwnd, wmNull, 0, 0)
	if cmd >= 1 && int(cmd) <= len(items) && items[cmd-1] != nil && items[cmd-1].enabled {
		go items[cmd-1].action()
	}
}
