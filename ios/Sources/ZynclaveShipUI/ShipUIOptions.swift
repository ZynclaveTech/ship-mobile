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
    /// The URL scheme a `deeplink` action must use, e.g. `myapp`: RFC 3986
    /// scheme syntax, and not `http` or `https` (web links are `web`
    /// actions). Anything else is a programming error and stops the app.
    public var deeplinkScheme: String {
        didSet { Self.checkScheme(deeplinkScheme) }
    }
    /// A theme token (`primary`, `accent`, `surface`, `surfaceVariant`, `onSurface`,
    /// `onSurfaceVariant`, `onPrimary`) to the app's colour. Resolution order:
    /// the release's `config/theme.json` for that token (when it is in
    /// `themeTokens`), then this, then an SDK default. Return `nil` to decline.
    public var color: (String) -> Color?
    /// Opens a `deeplink` action through the app's own routing.
    public var openDeeplink: (URL) -> Void
    /// The language code used to look up a release's OTA strings
    /// (`strings/<code>.json`): the app's chosen language. Default:
    /// ``deviceLanguage()``, the language the app is running in.
    public var language: () -> String
    /// A text `key` to the app's own (localised) string, or `nil` to use the
    /// definition's `fallback`. Text resolves as: the release's OTA string for
    /// `language()`, then this, then the literal fallback.
    ///
    /// The SDK also asks it for one reserved key of its own, `common_close`:
    /// the accessibility label of the close button on popups and cards
    /// (default "Close").
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
    /// button closed it, or `ShipPopups.close()`), and for a popup
    /// `popupPreflight` accepted that was then not shown after all. Release
    /// whatever `popupPreflight` loaded for it. Default: nothing.
    public var onPopupClosed: (SDUI.Popup) -> Void
    public var analytics: ShipAnalyticsSink?
    /// App-registered component types, rendered from their `fields`. The keys
    /// are the only extra `type`s the parser accepts; a built-in type name can
    /// never be replaced.
    ///
    /// A component that acts on a tap (navigates, opens something) should
    /// first call `@Environment(\.shipComponentWillAct)`, when it is not nil:
    /// inside a popup that closes the popup and logs `popup_action` with
    /// `component`, inside a card it logs `sdui_tap` with `component`, exactly
    /// as a built-in button does.
    ///
    ///     struct RoomTile: View {
    ///         let node: SDUI.CustomNode
    ///         @Environment(\.shipComponentWillAct) private var willAct
    ///         var body: some View {
    ///             Button("Join") { willAct?(); router.openRoom(node) }
    ///         }
    ///     }
    public var components: [String: (SDUI.CustomNode) -> AnyView]
    /// The bundle holding `ShipEmbedded/sdui/popups/*.json` (and the assets
    /// they name under `ShipEmbedded/`). They show without any Ship release.
    public var embeddedPopups: Bundle?
    /// The tokens a release's `config/theme.json` may override. Default:
    /// ``ShipTheme/defaultTokens``, the SDUI colour tokens. A host app with its
    /// own palette lists its own names here.
    public var themeTokens: Set<String>
    /// Opens the URL of a `web` action (buttons and card taps), e.g. in the
    /// app's own in-app browser. `nil` (the default) opens it the system way,
    /// through the SwiftUI `openURL` environment. Only `https` URLs reach it:
    /// the parser rejects any other `web` action.
    public var openWeb: ((URL) -> Void)?

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
        // Keep `openWeb` out of the last position: under Swift 5 language mode an
        // unlabeled trailing closure is matched backward, to the last closure-typed
        // parameter, so a 0.1.0 call `ShipUIOptions(deeplinkScheme:) { ... }` would
        // silently bind to it. `themeTokens` (not a closure) must stay after it.
        openWeb: ((URL) -> Void)? = nil,
        themeTokens: Set<String> = ShipTheme.defaultTokens
    ) {
        Self.checkScheme(deeplinkScheme)
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
        self.openWeb = openWeb
    }
}

extension ShipUIOptions {
    /// The language the app is running in, as a language code, e.g. `hi`:
    /// the first of `Bundle.main.preferredLocalizations` (the app's own
    /// localizations matched against the person's choices, per-app language
    /// included). An app with no localizations uses the device's first
    /// preferred language instead; `en` if there is none.
    public static func deviceLanguage() -> String {
        appLanguage(bundle: .main, preferredLanguages: Locale.preferredLanguages)
    }

    static func appLanguage(bundle: Bundle, preferredLanguages: [String]) -> String {
        func code(_ identifier: String) -> String? {
            guard identifier != "Base" else { return nil }
            return Locale(identifier: identifier).language.languageCode?.identifier
        }
        // An app with no localizations of its own reports its development
        // region; there is no language it "runs in", so the device's is used.
        let own = bundle.localizations.contains { $0 != "Base" }
            ? bundle.preferredLocalizations.lazy.compactMap(code).first : nil
        return own ?? preferredLanguages.lazy.compactMap(code).first ?? "en"
    }

    /// RFC 3986 `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`, and not a web scheme.
    static func isValidDeeplinkScheme(_ scheme: String) -> Bool {
        let s = Array(scheme.unicodeScalars)
        func alpha(_ c: Unicode.Scalar) -> Bool { ("a"..."z").contains(c) || ("A"..."Z").contains(c) }
        guard let first = s.first, alpha(first),
              s.allSatisfy({ alpha($0) || ("0"..."9").contains($0) || $0 == "+" || $0 == "-" || $0 == "." }) else {
            return false
        }
        let lower = scheme.lowercased()
        return lower != "http" && lower != "https"
    }

    private static func checkScheme(_ scheme: String) {
        precondition(isValidDeeplinkScheme(scheme),
                     "deeplinkScheme \"\(scheme)\" must be an RFC 3986 scheme (a letter, then letters, digits, +, - or .), and not http or https")
    }
}

/// What a rendered definition needs from its host, passed down the view tree.
struct ShipRuntime: @unchecked Sendable {
    var options: ShipUIOptions
    var content: ShipContent?

    static let none = ShipRuntime(options: ShipUIOptions(deeplinkScheme: "none"), content: nil)

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
    /// Which files these are: a release id, or `embedded` for the app's own.
    /// Decoded images are cached under it.
    var releaseID: String?
    var resolve: @Sendable (String) -> URL?
    func callAsFunction(_ asset: String) -> URL? { resolve(asset) }
}

extension EnvironmentValues {
    @Entry var shipRuntime: ShipRuntime = .none
    /// What a component must call just before it acts on a tap, or `nil`
    /// outside a popup or card. Built-in buttons call it; an app-registered
    /// component (see ``ShipUIOptions/components``) that navigates or opens
    /// something must call it too. Inside a popup it closes the popup first,
    /// so what the tap opens is not under it, and logs `popup_action` with
    /// `component`; inside a card it logs `sdui_tap` with `component`.
    public var shipComponentWillAct: (() -> Void)? {
        get { self[ShipComponentWillActKey.self] }
        set { self[ShipComponentWillActKey.self] = newValue }
    }
    /// A release path (`lottie/...`, `images/...`) to its file: the applied
    /// release by default; the app bundle inside a built-in popup.
    @Entry var sduiAssetURL = SDUIAssetResolver(releaseID: nil) { _ in nil }
}

private struct ShipComponentWillActKey: EnvironmentKey {
    static var defaultValue: (() -> Void)? { nil }
}
