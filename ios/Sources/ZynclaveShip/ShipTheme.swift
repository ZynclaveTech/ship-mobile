import Foundation

/// A color with components in 0...1, independent of UIKit and SwiftUI.
public struct ShipRGBA: Equatable, Sendable {
    public var r: Double
    public var g: Double
    public var b: Double
    public var a: Double

    public init(r: Double, g: Double, b: Double, a: Double) {
        self.r = r
        self.g = g
        self.b = b
        self.a = a
    }
}

/// Theme color overrides from `config/theme.json` of the applied release:
/// `{"primary300": "#FF7A00"}` or `{"primary300": {"light": "#..", "dark": "#.."}}`.
///
/// Only allowlisted tokens are honoured, and a bad value (wrong shape, bad
/// hex) means "no override", so a bad file can never make an app unreadable.
public enum ShipTheme {
    /// Theme tokens a release may override. Backgrounds, surfaces and text
    /// colors are deliberately absent.
    static let allowlist: Set<String> = ["primary300", "primary200", "accent500"]

    struct Pair: Equatable, Sendable {
        let light: ShipRGBA
        let dark: ShipRGBA
    }

    private static let cache = ReleaseCache<[String: Pair]>(empty: [:])

    static func parse(_ data: Data) -> [String: Pair] {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [:] }
        var out: [String: Pair] = [:]
        for (token, value) in obj where allowlist.contains(token) {
            if let hex = value as? String, let c = rgba(hex) {
                out[token] = Pair(light: c, dark: c)
            } else if let pair = value as? [String: Any],
                      let l = (pair["light"] as? String).flatMap(rgba),
                      let d = (pair["dark"] as? String).flatMap(rgba) {
                out[token] = Pair(light: l, dark: d)
            }
        }
        return out
    }

    /// Strict `#RRGGBB` (or `RRGGBB`); anything else is rejected rather than guessed.
    static func rgba(_ hex: String) -> ShipRGBA? {
        let h = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard h.count == 6, let v = UInt32(h, radix: 16) else { return nil }
        return ShipRGBA(r: Double((v >> 16) & 0xFF) / 255, g: Double((v >> 8) & 0xFF) / 255,
                        b: Double(v & 0xFF) / 255, a: 1)
    }

    static func color(_ token: String, dark: Bool, in theme: [String: Pair]) -> ShipRGBA? {
        guard let p = theme[token] else { return nil }
        return dark ? p.dark : p.light
    }

    /// The override for an allowlisted token in the applied release, or nil.
    public static func color(_ token: String, dark: Bool, in content: ShipContent) -> ShipRGBA? {
        let theme = cache.value(for: content) {
            content.url(for: "config/theme.json").flatMap { try? Data(contentsOf: $0) }.map(parse) ?? [:]
        }
        return color(token, dark: dark, in: theme)
    }
}
