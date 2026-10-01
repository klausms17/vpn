// Command mkicons draws the Windows app's icons into assets/: the app icon
// and a tray icon for each state of the tunnel, each as an .ico with every
// size Windows asks for from 100% to 300% scaling. Run it from windows/
// after changing a drawing: go run ./tools/mkicons
package main

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"log"
	"math"
	"os"
	"path/filepath"

	"golang.org/x/image/vector"
)

// The brand colours of klaus-page.html.
var (
	blue      = rgb(0x0A, 0x74, 0xFF)
	blueLight = rgb(0x3A, 0x93, 0xFF)
	mint      = rgb(0x2F, 0xC8, 0xA8)
	mist      = rgb(0x7D, 0x87, 0x99)
	red       = rgb(0xE5, 0x48, 0x4D)
	white     = rgb(0xFF, 0xFF, 0xFF)
)

func main() {
	appSizes := []int{16, 20, 24, 32, 40, 48, 64, 128, 256}
	traySizes := []int{16, 20, 24, 32, 40, 48, 64}
	for name, draw := range map[string]struct {
		paint func(*canvas)
		sizes []int
	}{
		"app.ico":             {paintApp, appSizes},
		"tray-off.ico":        {trayOff, traySizes},
		"tray-connecting.ico": {trayConnecting, traySizes},
		"tray-on.ico":         {trayOn, traySizes},
		"tray-error.ico":      {trayError, traySizes},
	} {
		if err := writeICO(filepath.Join("assets", name), draw.paint, draw.sizes); err != nil {
			log.Fatal(err)
		}
	}
}

// The shield of the Android launcher icon and of klaus-page.html, in a
// 24-unit square, and its check mark.
func shield(c *canvas) {
	c.move(12, 3)
	c.line(20, 6)
	c.line(20, 12)
	c.cube(20, 16.5, 16.6, 20.3, 12, 21)
	c.cube(7.4, 20.3, 4, 16.5, 4, 12)
	c.line(4, 6)
	c.close()
}

var check = [][2]float32{{9, 12}, {11, 14}, {15, 10}}

// paintApp is a rounded square in the brand gradient with a white shield
// and a blue check.
func paintApp(c *canvas) {
	c.fill(gradient, func(c *canvas) { c.roundRect(0.5, 0.5, 23, 23, 5.3) })
	c.fill(uniform(white), func(c *canvas) {
		c.transform(1.08, 12, 12.4) // a little larger, a little lower: optically centred
		shield(c)
	})
	c.fill(uniform(blue), func(c *canvas) {
		c.transform(1.08, 12, 12.4)
		c.stroke(check, 1.9)
	})
}

// The tray icons are the shield alone, as large as the icon allows.
func trayShield(c *canvas, fill image.Image) {
	c.fill(fill, func(c *canvas) {
		c.transform(1.3, 12, 12)
		shield(c)
	})
}

func trayOff(c *canvas) { trayShield(c, uniform(mist)) }

func trayConnecting(c *canvas) { trayShield(c, uniform(blueLight)) }

func trayOn(c *canvas) {
	trayShield(c, gradient)
	c.fill(uniform(white), func(c *canvas) {
		c.transform(1.3, 12, 12)
		c.stroke(check, 2.2)
	})
}

func trayError(c *canvas) {
	trayShield(c, uniform(mist))
	c.fill(uniform(red), func(c *canvas) { c.circle(18.5, 18.5, 5) })
	c.fill(uniform(white), func(c *canvas) { c.circle(18.5, 18.5, 2) })
}

// canvas draws shapes given in a 24-unit square onto a square image.
type canvas struct {
	img   *image.RGBA
	z     *vector.Rasterizer
	scale float32
	// The current transform: scale k about (cx, cy).
	k, cx, cy float32
}

func (c *canvas) pt(x, y float32) (float32, float32) {
	x, y = c.cx+(x-c.cx)*c.k, c.cy+(y-c.cy)*c.k
	return x * c.scale, y * c.scale
}

func (c *canvas) transform(k, cx, cy float32) { c.k, c.cx, c.cy = k, cx, cy }

func (c *canvas) move(x, y float32) { c.z.MoveTo(c.pt(x, y)) }
func (c *canvas) line(x, y float32) { c.z.LineTo(c.pt(x, y)) }
func (c *canvas) close()            { c.z.ClosePath() }

func (c *canvas) cube(x1, y1, x2, y2, x, y float32) {
	ax, ay := c.pt(x1, y1)
	bx, by := c.pt(x2, y2)
	px, py := c.pt(x, y)
	c.z.CubeTo(ax, ay, bx, by, px, py)
}

// fill paints with src the shapes that shapes draws.
func (c *canvas) fill(src image.Image, shapes func(*canvas)) {
	b := c.img.Bounds()
	c.z = vector.NewRasterizer(b.Dx(), b.Dy())
	c.k, c.cx, c.cy = 1, 0, 0
	shapes(c)
	mask := image.NewAlpha(b)
	c.z.Draw(mask, b, image.Opaque, image.Point{})
	draw.DrawMask(c.img, b, &scaled{src, b.Dx()}, image.Point{}, mask, image.Point{}, draw.Over)
}

// polygon adds a closed polygon, always wound the same way, so that
// overlapping shapes add up instead of cancelling.
func (c *canvas) polygon(pts [][2]float32) {
	var area float32
	for i, p := range pts {
		q := pts[(i+1)%len(pts)]
		area += p[0]*q[1] - q[0]*p[1]
	}
	if area < 0 {
		for i, j := 0, len(pts)-1; i < j; i, j = i+1, j-1 {
			pts[i], pts[j] = pts[j], pts[i]
		}
	}
	c.move(pts[0][0], pts[0][1])
	for _, p := range pts[1:] {
		c.line(p[0], p[1])
	}
	c.close()
}

func (c *canvas) circle(cx, cy, r float32) {
	const n = 64
	pts := make([][2]float32, n)
	for i := range pts {
		a := 2 * math.Pi * float64(i) / n
		pts[i] = [2]float32{cx + r*float32(math.Cos(a)), cy + r*float32(math.Sin(a))}
	}
	c.polygon(pts)
}

// stroke draws a line through pts, width w, with round ends and joins.
func (c *canvas) stroke(pts [][2]float32, w float32) {
	for i := range len(pts) - 1 {
		p, q := pts[i], pts[i+1]
		dx, dy := q[0]-p[0], q[1]-p[1]
		l := float32(math.Hypot(float64(dx), float64(dy)))
		nx, ny := -dy/l*w/2, dx/l*w/2
		c.polygon([][2]float32{{p[0] + nx, p[1] + ny}, {q[0] + nx, q[1] + ny}, {q[0] - nx, q[1] - ny}, {p[0] - nx, p[1] - ny}})
	}
	for _, p := range pts {
		c.circle(p[0], p[1], w/2)
	}
}

func (c *canvas) roundRect(x, y, w, h, r float32) {
	const k = 0.5523 // a quarter circle as a cubic curve
	c.move(x+r, y)
	c.line(x+w-r, y)
	c.cube(x+w-r+r*k, y, x+w, y+r-r*k, x+w, y+r)
	c.line(x+w, y+h-r)
	c.cube(x+w, y+h-r+r*k, x+w-r+r*k, y+h, x+w-r, y+h)
	c.line(x+r, y+h)
	c.cube(x+r-r*k, y+h, x, y+h-r+r*k, x, y+h-r)
	c.line(x, y+r)
	c.cube(x, y+r-r*k, x+r-r*k, y, x+r, y)
	c.close()
}

// gradient runs from the top left to the bottom right of the 24-unit
// square, as the mark of klaus-page.html.
var gradient = gradientImage{}

type gradientImage struct{}

func (gradientImage) ColorModel() color.Model { return color.RGBAModel }
func (gradientImage) Bounds() image.Rectangle { return image.Rect(0, 0, 24, 24) }

func (gradientImage) At(x, y int) color.Color {
	t := (float64(x) + float64(y)) / 46
	if t < 0.58 {
		return mix(blueLight, blue, t/0.58)
	}
	return mix(blue, mint, (t-0.58)/0.42)
}

// scaled shows a 24-unit fill at size pixels.
type scaled struct {
	src  image.Image
	size int
}

func (s *scaled) ColorModel() color.Model { return color.RGBAModel }
func (s *scaled) Bounds() image.Rectangle { return image.Rect(0, 0, s.size, s.size) }
func (s *scaled) At(x, y int) color.Color {
	return s.src.At(int(float64(x)+0.5)*24/s.size, int(float64(y)+0.5)*24/s.size)
}

func uniform(c color.RGBA) image.Image { return image.NewUniform(c) }

func rgb(r, g, b uint8) color.RGBA { return color.RGBA{r, g, b, 0xFF} }

func mix(a, b color.RGBA, t float64) color.RGBA {
	t = math.Max(0, math.Min(1, t))
	m := func(x, y uint8) uint8 { return uint8(math.Round(float64(x) + (float64(y)-float64(x))*t)) }
	return color.RGBA{m(a.R, b.R), m(a.G, b.G), m(a.B, b.B), 0xFF}
}

// writeICO draws paint at each size and stores the images as PNGs in one
// .ico file.
func writeICO(path string, paint func(*canvas), sizes []int) error {
	var images [][]byte
	for _, s := range sizes {
		c := &canvas{img: image.NewRGBA(image.Rect(0, 0, s, s)), scale: float32(s) / 24}
		paint(c)
		var b bytes.Buffer
		if err := png.Encode(&b, c.img); err != nil {
			return err
		}
		images = append(images, b.Bytes())
	}
	var out bytes.Buffer
	le := binary.LittleEndian
	head := make([]byte, 6)
	le.PutUint16(head[2:], 1) // icon
	le.PutUint16(head[4:], uint16(len(sizes)))
	out.Write(head)
	offset := 6 + 16*len(sizes)
	for i, s := range sizes {
		e := make([]byte, 16)
		e[0], e[1] = byte(s%256), byte(s%256) // 256 is written as 0
		le.PutUint16(e[4:], 1)                // planes
		le.PutUint16(e[6:], 32)               // bits per pixel
		le.PutUint32(e[8:], uint32(len(images[i])))
		le.PutUint32(e[12:], uint32(offset))
		offset += len(images[i])
		out.Write(e)
	}
	for _, img := range images {
		out.Write(img)
	}
	return os.WriteFile(path, out.Bytes(), 0o644)
}
