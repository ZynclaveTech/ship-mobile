import Combine
import os
import SwiftUI
import ZynclaveShip
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Server-driven popups: a Ship release's `sdui/popups/<id>.json`, plus the
/// app's own embedded ones, shown once as a modal over the app.
///
/// When one may appear (all must hold):
///  - not the first launch after install
///  - not shown before on this device (once per popup id, ever)
///  - at most one popup per app session
///  - `options.popupGate()` allows it (the app says "no" during a call, a
///    live room, a lock screen, or while signed out)
///
/// Create it once per launch, call `armSoon()` after each Ship sync, and attach
/// it with `.shipPopups(_:)`. It also re-checks each time the app becomes
/// active, so embedded popups show without any sync.
@MainActor
public final class ShipPopups: ObservableObject {
    /// The popup waiting for a moment to be drawn.
    @Published private(set) var armed: SDUI.Popup?

    private static let shownKey = "sdui.popups.shown"
    private static let launchesKey = "sdui.popups.launches"
    private static let log = Logger(subsystem: "com.zynclave.ship", category: "sdui")

    private let client: ShipClient
    let options: ShipUIOptions
    private let defaults: UserDefaults
    private let embedded: ShipEmbedded?
    private var shownThisSession = false
    private let isFirstLaunch: Bool
    nonisolated(unsafe) private var observer: NSObjectProtocol?
    private var cache: (release: String?, popups: [SDUI.Popup]) = (nil, [])

    public convenience init(client: ShipClient, options: ShipUIOptions) {
        self.init(client: client, options: options, defaults: .standard, observesLifecycle: true)
    }

    init(client: ShipClient, options: ShipUIOptions, defaults: UserDefaults, observesLifecycle: Bool) {
        self.client = client
        self.options = options
        self.defaults = defaults
        embedded = options.embeddedPopups.map {
            ShipEmbedded(bundle: $0, scheme: options.deeplinkScheme, customTypes: Set(options.components.keys))
        }
        let launches = defaults.integer(forKey: Self.launchesKey) + 1
        defaults.set(launches, forKey: Self.launchesKey)
        isFirstLaunch = launches <= 1
        if observesLifecycle {
            #if canImport(UIKit)
            let name = UIApplication.didBecomeActiveNotification
            #else
            let name = NSApplication.didBecomeActiveNotification
            #endif
            observer = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.armSoon() }
            }
            armSoon()
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    /// Re-evaluated 1.5 s from now, so the first frame belongs to the app.
    /// Call it after every Ship sync.
    public func armSoon() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.arm(now: .now)
        }
    }

    /// Dismisses the armed popup (the host's own close, e.g. on sign-out).
    public func close() { armed = nil }

    /// Tell the popup host the gate may have changed (a call ended).
    public func gateChanged() { objectWillChange.send() }

    func arm(now: Date) {
        guard armed == nil, !shownThisSession, !isFirstLaunch, options.popupGate(),
              let best = candidates(now: now).first else { return }
        armed = best
    }

    /// Popups that may be shown now, best first (priority, then id).
    func candidates(now: Date) -> [SDUI.Popup] {
        SDUI.eligiblePopups(allPopups(), now: now, shown: shown, eligible: options.eligibility().popups)
    }

    /// Whether to draw the armed popup now. Asked on every render.
    func visiblePopup() -> SDUI.Popup? {
        options.popupGate() ? armed : nil
    }

    /// Called when the popup is first actually on screen: it counts as seen
    /// from here, whatever the person does next.
    func markShown(_ popup: SDUI.Popup) {
        guard !shownThisSession else { return }
        shownThisSession = true
        var s = shown
        s.insert(popup.id)
        defaults.set(Array(s), forKey: Self.shownKey)
        analytics.popupShown(popup.id)
    }

    var analytics: ShipAnalytics {
        ShipAnalytics(sink: options.analytics, releaseID: { [content = client.content] in content.state?.releaseID })
    }

    var runtime: ShipRuntime { ShipRuntime(options: options, content: client.content) }

    private var shown: Set<String> { Set(defaults.stringArray(forKey: Self.shownKey) ?? []) }

    /// Ship's popups, then the app's own; Ship's win a shared id.
    private func allPopups() -> [SDUI.Popup] {
        let ship = shipPopups()
        let ids = Set(ship.map(\.id))
        return ship + (embedded?.popups ?? []).filter { !ids.contains($0.id) }
    }

    /// Where a popup's files (Lottie, images) are: Ship's release, or the app
    /// bundle for an embedded one.
    func assetURL(for popup: SDUI.Popup) -> @Sendable (String) -> URL? {
        let content = client.content
        if let embedded, embedded.popups.contains(where: { $0.id == popup.id }),
           !shipPopups().contains(where: { $0.id == popup.id }) {
            return { embedded.url(for: $0) }
        }
        return { content.url(for: $0) }
    }

    private func shipPopups() -> [SDUI.Popup] {
        let content = client.content
        guard let state = content.state, !state.revertToEmbedded else { return [] }
        if cache.release == state.releaseID { return cache.popups }
        let assets = Set(state.assets)
        let types = Set(options.components.keys)
        var out: [SDUI.Popup] = []
        for name in state.assets.sorted() where name.hasPrefix("sdui/popups/") && name.hasSuffix(".json") {
            guard let url = content.url(for: name), let data = try? Data(contentsOf: url) else { continue }
            do {
                let p = try SDUI.parsePopup(data, scheme: options.deeplinkScheme, customTypes: types,
                                            assetExists: assets.contains)
                guard !out.contains(where: { $0.id == p.id }) else {
                    Self.log.error("Popup \(name, privacy: .public) skipped: duplicate id")
                    continue
                }
                out.append(p)
            } catch let e as SDUI.Invalid {
                Self.log.error("Popup \(name, privacy: .public) skipped: \(e.reason, privacy: .public)")
            } catch {}
        }
        cache = (state.releaseID, out)
        return out
    }
}

// MARK: - View

struct ShipPopupView: View {
    let popup: SDUI.Popup
    let popups: ShipPopups
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openURL) private var openURL

    /// Closed without acting on it: the close button or a tap outside.
    private func closeTapped() {
        popups.analytics.popupAction(popup.id, action: .close)
        popups.close()
    }

    /// A button or app tile: counted, then the popup closes first; a deep link
    /// or web page opens over the app as usual.
    private func componentTapped() {
        popups.analytics.popupAction(popup.id, action: .component)
        popups.close()
    }

    var body: some View {
        let runtime = popups.runtime
        let dark = scheme == .dark
        ZStack {
            Color.black.opacity(0.5).ignoresSafeArea()
                .onTapGesture(perform: closeTapped)
                .accessibilityHidden(true)
            ZStack(alignment: .topTrailing) {
                ScrollView {
                    SDUINodeView(node: popup.body, onDismiss: { popups.close() })
                        .environment(\.sduiActionWillRun, SDUIActionHook(run: componentTapped))
                        .environment(\.sduiAssetURL, SDUIAssetResolver(resolve: popups.assetURL(for: popup)))
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: 560)
                .fixedSize(horizontal: false, vertical: true)
                if popup.closeButton {
                    Button(action: closeTapped) {
                        Image(systemName: "xmark").font(.subheadline.weight(.bold))
                            .foregroundStyle(runtime.color("onSurface", dark: dark).opacity(0.7))
                            .padding(12)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(verbatim: popups.options.appString("common_close") ?? "Close"))
                }
            }
            .background(runtime.color("surface", dark: dark), in: RoundedRectangle(cornerRadius: 24))
            .padding(.horizontal, 24)
            .accessibilityAddTraits(.isModal)
        }
        .environment(\.shipRuntime, runtime)
        .transition(.opacity)
    }
}

private struct ShipPopupsModifier: ViewModifier {
    @ObservedObject var popups: ShipPopups

    func body(content: Content) -> some View {
        content.overlay {
            if let popup = popups.visiblePopup() {
                ShipPopupView(popup: popup, popups: popups)
                    .id(popup.id)
                    .onAppear { popups.markShown(popup) }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: popups.armed?.id)
    }
}

extension View {
    /// Presents the armed server-driven popup over this view.
    public func shipPopups(_ popups: ShipPopups) -> some View {
        modifier(ShipPopupsModifier(popups: popups))
    }
}
