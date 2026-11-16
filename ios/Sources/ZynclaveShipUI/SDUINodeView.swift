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
            openURL(url)
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
    @Environment(\.sduiActionWillRun) private var actionWillRun
    @Environment(\.sduiAssetURL) private var assetURL

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
            if let url = assetURL(asset), let image = ShipImage.load(url) {
                Image(decorative: image, scale: 1).resizable()
                    .aspectRatio(aspect ?? CGFloat(image.width) / max(CGFloat(image.height), 1), contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
                    .accessibilityHidden(true)
            }
        case let .lottie(asset, height, loop):
            SDUILottie(asset: asset, height: height, loop: loop)
        case let .button(label, style, action):
            Button {
                actionWillRun?.run()
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

/// Image files from a release or the app bundle, decoded without UIKit/AppKit.
enum ShipImage {
    static func load(_ url: URL) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}
