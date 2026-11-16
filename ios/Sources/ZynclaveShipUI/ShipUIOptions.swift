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
    /// `onSurfaceVariant`, `onPrimary`) to the app's colour. Resolution order:
    /// the release's `config/theme.json` for that token (when it is in
    /// `themeTokens`), then this, then an SDK default. Return `nil` to decline.
    public var color: (String) -> Color?
    /// Opens a `deeplink` action through the app's own routing.
    public var openDeeplink: (URL) -> Void
    /// The language code used to look up a release's OTA strings
    /// (`strings/<code>.json`): the app's chosen language. Default: the
    /// device's preferred language code.
    public var language: () -> String
    /// A text `key` to the app's own (localised) string, or `nil` to use the
    /// definition's `fallback`. Text resolves as: the release's OTA string for
    /// `language()`, then this, then the literal fallback.
    public var appString: (String) -> String?
    /// Last step over every text, e.g. to replace `{first_name}`. Default:
    /// drops the placeholder, as for a user with no name.
    public var personalize: (String) -> String
    /// The ids the backend listed for the signed-in user. Targeted cards and
    /// popups show only when named here, so the default shows none of them.
    public var eligibility: () -> (cards: Set<String>, popups: Set<String>)
    /// Whether a popup may be on screen right now. Return `false` during a
    /// call, a live room, a lock screen, or while signed out. Default: always.
    public var popupGate: () -> Bool
    /// Asked before a selected popup is presented, e.g. to load the live room
    /// or community it shows. Return `false` to skip it this time: it is not
    /// marked shown, the next best candidate is tried, and the popup is
    /// considered again at the next `armSoon()`. Default: always `true`.
    public var popupPreflight: @MainActor (SDUI.Popup) async -> Bool
    /// Called when a popup that was showing closes (the person closed it, a
    /// button closed it, or `ShipPopups.close()`). Release whatever
    /// `popupPreflight` loaded for it. Default: nothing.
    public var onPopupClosed: (SDUI.Popup) -> Void
    public var analytics: ShipAnalyticsSink?
    /// App-registered component types, rendered from their `fields`. The keys
    /// are the only extra `type`s the parser accepts.
    public var components: [String: (SDUI.CustomNode) -> AnyView]
    /// The bundle holding `ShipEmbedded/sdui/popups/*.json` (and the assets
    /// they name under `ShipEmbedded/`). They show without any Ship release.
    public var embeddedPopups: Bundle?
    /// The tokens a release's `config/theme.json` may override. Default:
    /// ``ShipTheme/defaultTokens``, the SDUI colour tokens. A host app with its
    /// own palette lists its own names here.
    public var themeTokens: Set<String>

    public init(
        deeplinkScheme: String,
        color: @escaping (String) -> Color? = { _ in nil },
        openDeeplink: @escaping (URL) -> Void = { _ in },
        language: @escaping () -> String = ShipUIOptions.deviceLanguage,
        appString: @escaping (String) -> String? = { _ in nil },
        personalize: @escaping (String) -> String = { SDUI.personalize($0, firstName: nil) },
        eligibility: @escaping () -> (cards: Set<String>, popups: Set<String>) = { ([], []) },
        popupGate: @escaping () -> Bool = { true },
        popupPreflight: @escaping @MainActor (SDUI.Popup) async -> Bool = { _ in true },
        onPopupClosed: @escaping (SDUI.Popup) -> Void = { _ in },
        analytics: ShipAnalyticsSink? = nil,
        components: [String: (SDUI.CustomNode) -> AnyView] = [:],
        embeddedPopups: Bundle? = nil,
        themeTokens: Set<String> = ShipTheme.defaultTokens
    ) {
        self.deeplinkScheme = deeplinkScheme
        self.color = color
        self.openDeeplink = openDeeplink
        self.language = language
        self.appString = appString
        self.personalize = personalize
        self.eligibility = eligibility
        self.popupGate = popupGate
        self.popupPreflight = popupPreflight
        self.onPopupClosed = onPopupClosed
        self.analytics = analytics
        self.components = components
        self.embeddedPopups = embeddedPopups
        self.themeTokens = themeTokens
    }
}

extension ShipUIOptions {
    /// The device's preferred language code, e.g. `hi`.
    public static func deviceLanguage() -> String {
        Locale.preferredLanguages.first.flatMap { Locale(identifier: $0).language.languageCode?.identifier } ?? "en"
    }
}

/// What a rendered definition needs from its host, passed down the view tree.
struct ShipRuntime: @unchecked Sendable {
    var options: ShipUIOptions
    var content: ShipContent?

    static let none = ShipRuntime(options: ShipUIOptions(deeplinkScheme: ""), content: nil)

    /// Key through the release's OTA string in the app's language, then the
    /// app's own string; the literal fallback when neither has it. Then
    /// `personalize`.
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
        return content.string(key, locale: options.language())
    }

    /// The release theme (if the token is allowed), then the option colour,
    /// then the SDK default.
    func color(_ token: String, dark: Bool) -> Color {
        if let content, content.state?.revertToEmbedded == false,
           let c = ShipTheme.color(token, dark: dark, in: content, allowed: options.themeTokens) {
            return Color(.sRGB, red: c.r, green: c.g, blue: c.b, opacity: c.a)
        }
        if let c = options.color(token) { return c }
        return Self.defaultColor(token, dark: dark)
    }

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
