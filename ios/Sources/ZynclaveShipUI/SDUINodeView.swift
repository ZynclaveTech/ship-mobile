import ImageIO
import SwiftUI

@MainActor
enum SDUIActions {
    static func perform(_ action: SDUI.Action, options: ShipUIOptions, openURL: (URL) -> Void, dismiss: () -> Void) {
        switch action {
        case .deeplink(let url):
            // Through the app's own routing, so only routes it already supports do anything.
            options.openDeeplink(url)
        case .web(let url):
            if let openWeb = options.openWeb { openWeb(url) } else { openURL(url) }
        case .dismiss:
            dismiss()
        }
    }
}

/// Draws a parsed definition's node tree.
struct SDUINodeView: View {
    let node: SDUI.Node
    let onDismiss: () -> Void
    @Environment(\.shipRuntime) private var runtime
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openURL) private var openURL
    @Environment(\.shipComponentWillAct) private var componentWillAct

    private var dark: Bool { scheme == .dark }
    private func color(_ token: String) -> Color { runtime.color(token, dark: dark) }

    var body: some View {
        switch node {
        case let .stack(axis, spacing, padding, background, cornerRadius, children):
            Group {
                if axis == .vertical {
                    VStack(alignment: .leading, spacing: spacing) { childViews(children) }
                } else {
                    HStack(alignment: .center, spacing: spacing) { childViews(children) }
                }
            }
            .padding(padding)
            .frame(maxWidth: axis == .vertical ? .infinity : nil, alignment: .leading)
            .background(background.map(color) ?? .clear, in: RoundedRectangle(cornerRadius: cornerRadius))
        case let .text(value, style, maxLines, tint):
            Text(verbatim: runtime.text(value))
                .font(Self.font(style))
                .foregroundStyle(color(tint ?? "onSurface"))
                .lineLimit(maxLines)
                .fixedSize(horizontal: false, vertical: true)
        case let .image(asset, aspect, cornerRadius):
            SDUIImage(asset: asset, aspect: aspect, cornerRadius: cornerRadius)
        case let .lottie(asset, height, loop):
            SDUILottie(asset: asset, height: height, loop: loop)
        case let .button(label, style, action):
            Button {
                componentWillAct?()
                SDUIActions.perform(action, options: runtime.options, openURL: { openURL($0) }, dismiss: onDismiss)
            } label: {
                Text(verbatim: runtime.text(label)).font(.subheadline.weight(.semibold))
                    .frame(maxWidth: style == "text" ? nil : .infinity)
                    .padding(.vertical, style == "text" ? 0 : 10)
            }
            .buttonStyle(.plain)
            .foregroundStyle(style == "primary" ? color("onPrimary") : color("primary"))
            .background(style == "primary" ? color("primary") : style == "secondary" ? color("surfaceVariant") : .clear,
                        in: Capsule())
        case let .badge(value, tint):
            Text(verbatim: runtime.text(value)).font(.caption2.weight(.bold))
                .padding(.horizontal, 8).padding(.vertical, 3)
                .foregroundStyle(color("onPrimary"))
                .background(color(tint ?? "primary"), in: Capsule())
        case let .spacer(height):
            Color.clear.frame(height: height)
        case let .custom(custom):
            if let make = runtime.options.components[custom.type] { make(custom) }
        }
    }

    @ViewBuilder private func childViews(_ children: [SDUI.Node]) -> some View {
        ForEach(Array(children.enumerated()), id: \.offset) { _, child in
            SDUINodeView(node: child, onDismiss: onDismiss)
        }
    }

    static func font(_ style: String) -> Font {
        switch style {
        case "title": return .title3.weight(.bold)
        case "headline": return .headline
        case "caption": return .caption
        default: return .subheadline
        }
    }
}

/// A release image, drawn as wide as it is given. Decoded off the main
/// thread at the width it is drawn, once per (release, asset): scrolling a card
/// away and back, or a redraw, reuses the decode.
struct SDUIImage: View {
    let asset: String
    let aspect: CGFloat?
    let cornerRadius: CGFloat
    @Environment(\.sduiAssetURL) private var assetURL
    @Environment(\.displayScale) private var displayScale
    @State private var width: CGFloat = 0
    @State private var loaded: (key: String, image: CGImage?)?

    var body: some View {
        if let url = assetURL(asset), let release = assetURL.releaseID,
           let size = ShipImageCache.shared.size(release: release, asset: asset, url: url) {
            let key = "\(release)|\(asset)"
            let pixels = Int((width * displayScale).rounded(.up))
            let image = ShipImageCache.shared.cached(release: release, asset: asset, maxWidth: pixels)
                ?? loaded.flatMap { $0.key == key ? $0.image : nil }
            Color.clear
                .aspectRatio(aspect ?? size.width / max(size.height, 1), contentMode: .fit)
                .overlay {
                    if let image {
                        Image(decorative: image, scale: 1).resizable()
                            .aspectRatio(contentMode: .fit)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                .task(id: "\(key)|\(pixels)") {
                    guard pixels > 0, ShipImageCache.shared.cached(release: release, asset: asset, maxWidth: pixels) == nil
                    else { return }
                    let decoded = await Task.detached(priority: .userInitiated) {
                        ShipImageCache.shared.load(release: release, asset: asset, url: url, maxWidth: pixels)
                    }.value
                    loaded = (key, decoded)
                }
                .accessibilityHidden(true)
        }
    }
}

/// Decoded release images, one per (release, asset), like Android's. A decode
/// serves any width up to the one it was made for; a wider one (a rotation)
/// decodes again. Only the latest release's images are kept.
final class ShipImageCache: @unchecked Sendable {
    static let shared = ShipImageCache()

    private struct Entry {
        let release: String
        let image: CGImage?
        let forWidth: Int
    }

    private let lock = NSLock()
    private var images: [String: Entry] = [:]
    private var sizes: [String: CGSize?] = [:]
    /// Tests only: how many times a file was decoded.
    private(set) var decodes = 0

    /// The image's drawn size in pixels (EXIF orientation applied), read from
    /// its header without decoding; nil if it cannot be read or is larger than
    /// ``ShipImage/maxSide`` on a side, so it is never drawn.
    func size(release: String, asset: String, url: URL) -> CGSize? {
        let key = "\(release)|\(asset)"
        lock.lock()
        if let known = sizes[key] { lock.unlock(); return known }
        lock.unlock()
        let size = ShipImage.size(url)
        lock.lock()
        sizes[key] = .some(size)
        lock.unlock()
        return size
    }

    func cached(release: String, asset: String, maxWidth: Int) -> CGImage? {
        lock.lock()
        defer { lock.unlock() }
        guard let e = images[asset], e.release == release, e.forWidth >= maxWidth else { return nil }
        return e.image
    }

    /// Decodes at most once per (release, asset, width up to the last one).
    func load(release: String, asset: String, url: URL, maxWidth: Int) -> CGImage? {
        lock.lock()
        if let e = images[asset], e.release == release, e.forWidth >= maxWidth {
            lock.unlock()
            return e.image
        }
        decodes += 1
        lock.unlock()
        let image = ShipImage.load(url, maxWidth: maxWidth)
        lock.lock()
        images = images.filter { $0.value.release == release }
        sizes = sizes.filter { $0.key.hasPrefix("\(release)|") }
        images[asset] = Entry(release: release, image: image, forWidth: maxWidth)
        lock.unlock()
        return image
    }

    /// Tests only.
    func removeAll() {
        lock.lock()
        images = [:]
        sizes = [:]
        decodes = 0
        lock.unlock()
    }
}

/// Image files from a release or the app bundle, decoded without UIKit/AppKit.
/// EXIF orientation is applied, as `UIImage(contentsOfFile:)` does.
enum ShipImage {
    /// Larger than this on either side is refused, as on Android: a release
    /// must not be able to exhaust memory.
    static let maxSide = 4096

    /// The size as drawn (a quarter-turn EXIF orientation swaps the sides),
    /// or nil when unreadable or over ``maxSide`` on a side.
    static func size(_ url: URL) -> CGSize? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int,
              let h = props[kCGImagePropertyPixelHeight] as? Int,
              (1...maxSide).contains(w), (1...maxSide).contains(h) else { return nil }
        let orientation = props[kCGImagePropertyOrientation] as? Int ?? 1
        return orientation >= 5 ? CGSize(width: h, height: w) : CGSize(width: w, height: h)
    }

    /// Decodes `url` upright, no wider (as drawn) than `maxWidth` pixels and
    /// never upscaled. Nil when it fails or is over ``maxSide`` on a side.
    static func load(_ url: URL, maxWidth: Int = .max) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let drawn = size(url) else { return nil }
        // The thumbnail size caps the LONGER side; scale it so the drawn width fits.
        let longest = max(drawn.width, drawn.height)
        let scale = min(1, CGFloat(max(maxWidth, 1)) / drawn.width)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, Int((longest * scale).rounded(.up))),
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
