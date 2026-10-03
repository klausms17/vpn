// Package assets holds the app's icons, drawn by tools/mkicons. The
// installer and the programs' resources use app.ico from here as well.
package assets

import _ "embed"

var (
	//go:embed app.ico
	App []byte
	//go:embed tray-off.ico
	TrayOff []byte
	//go:embed tray-connecting.ico
	TrayConnecting []byte
	//go:embed tray-on.ico
	TrayOn []byte
	//go:embed tray-error.ico
	TrayError []byte
)
