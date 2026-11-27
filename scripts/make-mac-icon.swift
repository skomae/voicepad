// Draws the Mac app icon and writes every size the asset catalog needs.
//   swift scripts/make-mac-icon.swift VoicePadMac/Assets.xcassets/AppIcon.appiconset
import AppKit

let canvas: CGFloat = 1024

func drawIcon(in ctx: CGContext) {
    // macOS icon grid: an 824 pt rounded square centred on the 1024 canvas.
    let body = CGRect(x: 100, y: 100, width: 824, height: 824)
    let radius: CGFloat = 185
    let shape = CGPath(roundedRect: body, cornerWidth: radius, cornerHeight: radius, transform: nil)

    // Soft drop shadow under the tile.
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -12), blur: 28, color: NSColor.black.withAlphaComponent(0.28).cgColor)
    ctx.addPath(shape)
    ctx.setFillColor(NSColor.black.cgColor)
    ctx.fillPath()
    ctx.restoreGState()

    // Red tile, lighter at the top.
    ctx.saveGState()
    ctx.addPath(shape)
    ctx.clip()
    let colors = [NSColor(srgbRed: 1.00, green: 0.42, blue: 0.36, alpha: 1).cgColor,
                  NSColor(srgbRed: 0.86, green: 0.13, blue: 0.20, alpha: 1).cgColor] as CFArray
    let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: colors, locations: [0, 1])!
    ctx.drawLinearGradient(gradient, start: CGPoint(x: 512, y: body.maxY), end: CGPoint(x: 512, y: body.minY), options: [])
    // A faint sheen across the upper half.
    let sheen = [NSColor.white.withAlphaComponent(0.18).cgColor, NSColor.white.withAlphaComponent(0).cgColor] as CFArray
    let sheenGradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: sheen, locations: [0, 1])!
    ctx.drawLinearGradient(sheenGradient, start: CGPoint(x: 512, y: body.maxY), end: CGPoint(x: 512, y: body.midY), options: [])
    ctx.restoreGState()

    // White speech bubble with a tail at the lower left.
    let bubble = CGRect(x: 232, y: 330, width: 560, height: 400)
    let bubblePath = CGMutablePath()
    bubblePath.addRoundedRect(in: bubble, cornerWidth: 150, cornerHeight: 150)
    bubblePath.move(to: CGPoint(x: 300, y: 380))
    bubblePath.addCurve(to: CGPoint(x: 250, y: 250), control1: CGPoint(x: 300, y: 320), control2: CGPoint(x: 285, y: 275))
    bubblePath.addCurve(to: CGPoint(x: 420, y: 345), control1: CGPoint(x: 330, y: 255), control2: CGPoint(x: 385, y: 300))
    bubblePath.closeSubpath()
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -6), blur: 18, color: NSColor.black.withAlphaComponent(0.18).cgColor)
    ctx.addPath(bubblePath)
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.fillPath()
    ctx.restoreGState()

    // A waveform inside the bubble, in the tile's red.
    let heights: [CGFloat] = [90, 170, 250, 140, 210, 120, 70]
    let barWidth: CGFloat = 38, gap: CGFloat = 26
    let total = CGFloat(heights.count) * barWidth + CGFloat(heights.count - 1) * gap
    var x = bubble.midX - total / 2
    ctx.setFillColor(NSColor(srgbRed: 0.90, green: 0.18, blue: 0.22, alpha: 1).cgColor)
    for h in heights {
        let bar = CGRect(x: x, y: bubble.midY - h / 2, width: barWidth, height: h)
        ctx.addPath(CGPath(roundedRect: bar, cornerWidth: barWidth / 2, cornerHeight: barWidth / 2, transform: nil))
        ctx.fillPath()
        x += barWidth + gap
    }
}

func render(size: Int) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    let context = NSGraphicsContext(bitmapImageRep: rep)!
    NSGraphicsContext.current = context
    let ctx = context.cgContext
    ctx.scaleBy(x: CGFloat(size) / canvas, y: CGFloat(size) / canvas)
    drawIcon(in: ctx)
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

let out = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? ".", isDirectory: true)
try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
var images: [[String: String]] = []
for point in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let name = "icon_\(point)x\(point)\(scale == 2 ? "@2x" : "").png"
        try render(size: point * scale).write(to: out.appendingPathComponent(name))
        images.append(["idiom": "mac", "size": "\(point)x\(point)", "scale": "\(scale)x", "filename": name])
    }
}
let contents: [String: Any] = ["images": images, "info": ["author": "xcode", "version": 1]]
try JSONSerialization.data(withJSONObject: contents, options: [.prettyPrinted, .sortedKeys])
    .write(to: out.appendingPathComponent("Contents.json"))
print("wrote \(images.count) icons to \(out.path)")
