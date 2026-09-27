//go:build tools

package libxray

// Keeps golang.org/x/mobile in go.mod so `gomobile bind` works offline in CI.
import _ "golang.org/x/mobile/bind"
