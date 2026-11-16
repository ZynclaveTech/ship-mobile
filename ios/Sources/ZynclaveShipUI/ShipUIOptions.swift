import SwiftUI
import ZynclaveShip

/// Receives the analytics events the views raise: `sdui_impression`,
/// `sdui_tap`, `sdui_dismiss`, `popup_shown` and `popup_action`. Forward them
/// to whatever the app uses, behind its own consent gate.
public protocol ShipAnalyticsSink {
    func log(_ name: String, _ params: [String: String])
}

/// Everything app-specific the views need. The library never reaches for the
/// app's navigation, strings, colours, user or analytics: it asks here.
///
/// Closures are called on the main actor only.
public struct ShipUIOptions: @unchecked Sendable {
    /// The URL scheme a `deeplink` action must use, e.g. `myapp`.
    public var deeplinkScheme: String
    /// A theme token (`primary`, `accent`, `surface`, `surfaceVariant`, `onSurface`,
    /// `onSurfaceVariant`, `onPrimary`) to the app's colour. `nil` falls back to
    /// the release's `config/theme.json`, then to an SDK default.
    public var color: (String) -> Color?
    /// Opens a `deeplink` action through the app's own routing.
    public var openDeeplink: (URL) -> Void
    /// A text `key` to the app's own (localised) string, or `nil` to use the
    /// definition's `fallback`. A release's OTA string for the key wins first.
    public var appString: (String) -> String?
    /// Last step over every text, e.g. to replace `{first_name}`. Default: identity.
    public var personalize: (String) -> String
    /// The ids the backend listed for the signed-in user. Targeted cards and
    /// popups show only when named here, so the default shows none of them.
    public var eligibility: () -> (cards: Set<String>, popups: Set<String>)
    /// Whether a popup may be on screen right now. Return `false` during a
    /// call, a live room, a lock screen, or while signed out. Default: always.
    public var popupGate: () -> Bool
    public var analytics: ShipAnalyticsSink?
    /// App-registered component types, rendered from their `fields`. The keys
    /// are the only extra `type`s the parser accepts.
    public var components: [String: (SDUI.CustomNode) -> AnyView]
    /// The bundle holding `ShipEmbedded/sdui/popups/*.json` (and the assets
    /// they name under `ShipEmbedded/`). They show without any Ship release.
    public var embeddedPopups: Bundle?

    public init(
        deeplinkScheme: String,
        color: @escaping (String) -> Color? = { _ in nil },
        openDeeplink: @escaping (URL) -> Void = { _ in },
        appString: @escaping (String) -> String? = { _ in nil },
        personalize: @escaping (String) -> String = { $0 },
        eligibility: @escaping () -> (cards: Set<String>, popups: Set<String>) = { ([], []) },
        popupGate: @escaping () -> Bool = { true },
        analytics: ShipAnalyticsSink? = nil,
        components: [String: (SDUI.CustomNode) -> AnyView] = [:],
        embeddedPopups: Bundle? = nil
    ) {
        self.deeplinkScheme = deeplinkScheme
        self.color = color
        self.openDeeplink = openDeeplink
        self.appString = appString
        self.personalize = personalize
        self.eligibility = eligibility
        self.popupGate = popupGate
        self.analytics = analytics
        self.components = components
        self.embeddedPopups = embeddedPopups
    }
}

/// What a rendered definition needs from its host, passed down the view tree.
struct ShipRuntime: @unchecked Sendable {
    var options: ShipUIOptions
    var content: ShipContent?

    static let none = ShipRuntime(options: ShipUIOptions(deeplinkScheme: ""), content: nil)

    /// Key through the release's OTA strings, then the app's own; the
    /// fallback when neither has it. Then `personalize`.
    func text(_ v: SDUI.TextValue) -> String {
        var text = v.fallback ?? ""
        if let key = v.key {
            if let ota = otaString(key) {
                text = ota
            } else if let s = options.appString(key) {
                text = s
            }
        }
        return options.personalize(text)
    }

    private func otaString(_ key: String) -> String? {
        guard let content, content.state?.revertToEmbedded == false else { return nil }
        let language = Locale.current.language.languageCode?.identifier ?? "en"
        return content.string(key, locale: language) ?? (language == "en" ? nil : content.string(key, locale: "en"))
    }

    /// Option colour, then the release theme, then the SDK default.
    func color(_ token: String, dark: Bool) -> Color {
        if let c = options.color(token) { return c }
        if let content, content.state?.revertToEmbedded == false,
           let name = Self.themeNames[token],
           let c = ShipTheme.color(name, dark: dark, in: content) {
            return Color(.sRGB, red: c.r, green: c.g, blue: c.b, opacity: c.a)
        }
        return Self.defaultColor(token, dark: dark)
    }

    /// SDUI tokens that a release's `config/theme.json` may override.
    static let themeNames = ["primary": "primary300", "accent": "accent500"]

    static func defaultColor(_ token: String, dark: Bool) -> Color {
        func hex(_ v: UInt32) -> Color {
            Color(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255,
                  blue: Double(v & 0xFF) / 255, opacity: 1)
        }
        switch token {
        case "primary": return hex(dark ? 0x8B7CFF : 0x5B4BDB)
        case "accent": return hex(dark ? 0xFF9A3D : 0xE86F00)
        case "surface": return hex(dark ? 0x1C1C1E : 0xFFFFFF)
        case "surfaceVariant": return hex(dark ? 0x2C2C2E : 0xF2F2F7)
        case "onPrimary": return .white
        case "onSurfaceVariant": return hex(dark ? 0xA0A0AB : 0x6B6B76)
        default: return hex(dark ? 0xF2F2F7 : 0x1C1C1E)
        }
    }
}

/// Wrapped in structs so the environment does not see a bare closure.
struct SDUIAssetResolver: Sendable {
    var resolve: @Sendable (String) -> URL?
    func callAsFunction(_ asset: String) -> URL? { resolve(asset) }
}

struct SDUIActionHook {
    var run: () -> Void
}

extension EnvironmentValues {
    @Entry var shipRuntime: ShipRuntime = .none
    /// Run before an SDUI button's action (a popup closes itself first).
    @Entry var sduiActionWillRun: SDUIActionHook?
    /// A release path (`lottie/...`, `images/...`) to its file: the applied
    /// release by default; the app bundle inside a built-in popup.
    @Entry var sduiAssetURL = SDUIAssetResolver { _ in nil }
}
