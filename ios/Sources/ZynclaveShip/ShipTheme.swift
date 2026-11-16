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
/// `{"primary": "#FF7A00"}` or `{"primary": {"light": "#..", "dark": "#.."}}`.
///
/// Only allowed tokens are honoured (the lookups take the allowed set,
/// default ``defaultTokens``), and a bad value (wrong shape, bad
/// hex) means "no override", so a bad file can never make an app unreadable.
public enum ShipTheme {
    /// The SDUI colour tokens a release may override by default, the ones the
    /// Ship validator accepts. A host app with its own palette passes its own
    /// names as `allowed` to the lookups.
    public static let defaultTokens: Set<String> = [
        "primary", "accent", "surface", "surfaceVariant", "onSurface", "onSurfaceVariant", "onPrimary",
    ]

    struct Pair: Equatable, Sendable {
        let light: ShipRGBA
        let dark: ShipRGBA
    }

    private static let cache = ReleaseCache<[String: Pair]>(empty: [:])

    /// Parses `data`, keeping only the tokens in `allowed`.
    static func parse(_ data: Data, allowed: Set<String> = defaultTokens) -> [String: Pair] {
        parseAll(data).filter { allowed.contains($0.key) }
    }

    /// Every token with a valid value, whatever its name.
    private static func parseAll(_ data: Data) -> [String: Pair] {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [:] }
        var out: [String: Pair] = [:]
        for (token, value) in obj {
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
        // Six hex digits and nothing else: UInt32(_:radix:) alone also takes a
        // sign, so "#+12345" would pass.
        guard h.unicodeScalars.count == 6, h.unicodeScalars.allSatisfy({ $0.properties.isASCIIHexDigit }),
              let v = UInt32(h, radix: 16) else { return nil }
        return ShipRGBA(r: Double((v >> 16) & 0xFF) / 255, g: Double((v >> 8) & 0xFF) / 255,
                        b: Double(v & 0xFF) / 255, a: 1)
    }

    static func color(_ token: String, dark: Bool, in theme: [String: Pair]) -> ShipRGBA? {
        guard let p = theme[token] else { return nil }
        return dark ? p.dark : p.light
    }

    /// The override for `token` in the applied release, or nil. Only tokens in
    /// `allowed` are honoured. The cache holds the unfiltered parse, so it is
    /// right for any `allowed` set.
    public static func color(_ token: String, dark: Bool, in content: ShipContent,
                             allowed: Set<String> = defaultTokens) -> ShipRGBA? {
        guard allowed.contains(token) else { return nil }
        let theme = cache.value(for: content) {
            content.url(for: "config/theme.json").flatMap { try? Data(contentsOf: $0) }.map(parseAll) ?? [:]
        }
        return color(token, dark: dark, in: theme)
    }
}
