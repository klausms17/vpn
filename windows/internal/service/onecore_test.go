package service

import (
	"go/ast"
	"go/parser"
	"go/token"
	"path/filepath"
	"slices"
	"testing"
)

// TestTheServiceMakesNoCoreOfItsOwn: every Xray core takes over the
// process's log, DNS and outbounds when it is made, and the tunnel runs in
// this process. Only the tunnel's controller gives them back (its
// ProbeOutbounds, FetchThroughTunnel and RequestThroughTunnel), so these
// never run here (the plan's row 18).
func TestTheServiceMakesNoCoreOfItsOwn(t *testing.T) {
	ownCore := []string{"MeasureOutboundDelay", "Fetch", "FetchWithHeaders", "Request", "ValidateConfig"}
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	for _, name := range files {
		f, err := parser.ParseFile(fset, name, nil, 0)
		if err != nil {
			t.Fatal(err)
		}
		ast.Inspect(f, func(n ast.Node) bool {
			if sel, ok := n.(*ast.SelectorExpr); ok {
				if pkg, ok := sel.X.(*ast.Ident); ok && pkg.Name == "libxray" && slices.Contains(ownCore, sel.Sel.Name) {
					t.Errorf("%s: libxray.%s makes a core of its own", fset.Position(sel.Pos()), sel.Sel.Name)
				}
			}
			return true
		})
	}
}
