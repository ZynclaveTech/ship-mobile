import Combine
import os
import SwiftUI
import ZynclaveShip

/// The server-driven cards of the applied Ship release, for the host's feed.
/// The host decides where a card goes (the card's `position` counts posts, not
/// rows) and draws each with `ShipCardView`.
@MainActor
public final class ShipCards: ObservableObject {
    private static let log = Logger(subsystem: "com.zynclave.ship", category: "sdui")
    private static let dismissedKey = "sdui.dismissed"

    private let client: ShipClient
    let options: ShipUIOptions
    private let defaults: UserDefaults
    private var cache: (release: String?, cards: [SDUI.Card]) = (nil, [])
    /// The dismissed set this instance last published for, so a change made
    /// through another instance (same defaults) is told apart from our own.
    private var lastDismissed: Set<String>
    private var observer: DefaultsObserver?

    public convenience init(client: ShipClient, options: ShipUIOptions) {
        self.init(client: client, options: options, defaults: .standard)
    }

    init(client: ShipClient, options: ShipUIOptions, defaults: UserDefaults) {
        self.client = client
        self.options = options
        self.defaults = defaults
        self.lastDismissed = Set(defaults.stringArray(forKey: Self.dismissedKey) ?? [])
        // Another ShipCards on the same defaults dismissing a card shows up
        // here; this instance then drops it at once as well.
        observer = DefaultsObserver(defaults) { [weak self] in
            if Thread.isMainThread {
                MainActor.assumeIsolated { self?.defaultsChanged() }
            } else {
                Task { @MainActor in self?.defaultsChanged() }
            }
        }
    }

    private func defaultsChanged() {
        let now = dismissed
        guard now != lastDismissed else { return }
        lastDismissed = now
        objectWillChange.send()
    }

    var runtime: ShipRuntime { ShipRuntime(options: options, content: client.content) }
    var analytics: ShipAnalytics {
        ShipAnalytics(sink: options.analytics, releaseID: { [content = client.content] in content.state?.releaseID })
    }

    /// The cards to show on `tab` (`discover`, `following`): valid, in
    /// schedule, not dismissed, targeted ones only if `options.eligibility`
    /// names them, best first, at most `SDUI.maxCardsPerFeed`. Empty after a
    /// revert to the embedded content.
    public func cards(tab: String, now: Date = .now) -> [SDUI.Card] {
        SDUI.cards(all(), tab: tab, now: now, dismissed: dismissed, eligible: options.eligibility().cards)
    }

    /// Dismisses a card for good, and reports it to analytics once however
    /// many times it is tapped.
    public func dismiss(_ id: String) {
        var d = dismissed
        guard d.insert(id).inserted else { return }
        lastDismissed = d
        defaults.set(Array(d), forKey: Self.dismissedKey)
        analytics.dismiss(id)
        objectWillChange.send()
    }

    /// Call after a Ship sync applied a release, or when `options.eligibility`
    /// changed: views observing this object redraw and ask ``cards(tab:now:)``
    /// again.
    public func refresh() {
        objectWillChange.send()
    }

    /// Reports that a card counted as seen (see `SDUIImpressionTracker`).
    public func reportImpression(_ id: String) { analytics.impression(id) }

    var dismissed: Set<String> { Set(defaults.stringArray(forKey: Self.dismissedKey) ?? []) }

    /// Parsed once per release. Invalid cards are logged and skipped.
    func all() -> [SDUI.Card] {
        guard let state = client.content.state, !state.revertToEmbedded else { return [] }
        if cache.release == state.releaseID { return cache.cards }
        let content = client.content
        let assets = Set(state.assets)
        let types = Set(options.components.keys)
        var cards: [SDUI.Card] = []
        // Sorted, so which file wins a duplicate id is deterministic.
        for name in state.assets.sorted() where name.hasPrefix("sdui/cards/") && name.hasSuffix(".json") {
            guard let url = content.url(for: name), let data = try? Data(contentsOf: url) else { continue }
            do {
                let card = try SDUI.parseCard(data, scheme: options.deeplinkScheme, customTypes: types,
                                              assetExists: assets.contains)
                guard !cards.contains(where: { $0.id == card.id }) else {
                    Self.log.error("Card \(name, privacy: .public) skipped: duplicate id \(card.id, privacy: .public)")
                    continue
                }
                cards.append(card)
            } catch let e as SDUI.Invalid {
                Self.log.error("Card \(name, privacy: .public) skipped: \(e.reason, privacy: .public)")
            } catch {}
        }
        cache = (state.releaseID, cards)
        return cards
    }
}

/// A server-driven card: its body, a tap action and a close button.
public struct ShipCardView: View {
    let card: SDUI.Card
    @ObservedObject var cards: ShipCards
    @Environment(\.openURL) private var openURL
    @Environment(\.colorScheme) private var scheme

    public init(card: SDUI.Card, cards: ShipCards) {
        self.card = card
        self.cards = cards
    }

    private func perform(_ action: SDUI.Action) {
        SDUIActions.perform(action, options: cards.options, openURL: { openURL($0) }, dismiss: { cards.dismiss(card.id) })
    }

    public var body: some View {
        let content = cards.runtime.content
        ZStack(alignment: .topTrailing) {
            SDUINodeView(node: card.body, onDismiss: { cards.dismiss(card.id) })
                // A button or app tile inside the card runs this first.
                .environment(\.shipComponentWillAct, { cards.analytics.tap(card.id, target: .component) })
                .contentShape(Rectangle())
                .onTapGesture {
                    guard let tap = card.tap else { return }
                    cards.analytics.tap(card.id, target: .card)
                    perform(tap)
                }
            if card.dismissible {
                Button { cards.dismiss(card.id) } label: {
                    Image(systemName: "xmark").font(.caption.weight(.bold))
                        .foregroundStyle(cards.runtime.color("onSurface", dark: scheme == .dark).opacity(0.6))
                        .padding(8)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: cards.options.appString("common_close") ?? "Close"))
            }
        }
        .environment(\.shipRuntime, cards.runtime)
        .environment(\.sduiAssetURL, SDUIAssetResolver(releaseID: content?.state?.releaseID) { content?.url(for: $0) })
    }
}

/// Calls `onChange` whenever `defaults` change; stops when released.
private final class DefaultsObserver: @unchecked Sendable {
    private let token: NSObjectProtocol

    init(_ defaults: UserDefaults, onChange: @escaping @Sendable () -> Void) {
        token = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification, object: defaults, queue: nil
        ) { _ in onChange() }
    }

    deinit { NotificationCenter.default.removeObserver(token) }
}
