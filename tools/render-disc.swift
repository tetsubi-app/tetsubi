// Draws the disc artwork (an SVG kept outside the repo) into a square PNG.
// NSImage renders the SVG's gradient; ImageMagick drops it.
//
//   swiftc -O tools/render-disc.swift -o /tmp/render-disc
//   /tmp/render-disc <disc.svg> <out.png> <canvas px> <disc diameter px> <navy|none>
//
// The SVG's disc is 880 of its 1000 viewBox. Sizes in use (see docs/icons.md):
// iOS and Mac icons 746 of 1024, on navy; Android adaptive layers 45.9 dp of
// 108 dp, transparent.
import AppKit
let a = CommandLine.arguments
let img = NSImage(contentsOf: URL(fileURLWithPath: a[1]))!
let canvas = Int(a[3])!, disc = Double(a[4])!
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: canvas, pixelsHigh: canvas,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
rep.size = NSSize(width: canvas, height: canvas)
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current!.imageInterpolation = .high
let c = Double(canvas)
if a[5] == "navy" {
    NSColor(deviceRed: 0, green: 11/255, blue: 37/255, alpha: 1).setFill()
    NSRect(x: 0, y: 0, width: c, height: c).fill()
}
let side = disc / 0.88
img.draw(in: NSRect(x: (c - side) / 2, y: (c - side) / 2, width: side, height: side),
         from: .zero, operation: .sourceOver, fraction: 1)
NSGraphicsContext.current!.flushGraphics()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: a[2]))
