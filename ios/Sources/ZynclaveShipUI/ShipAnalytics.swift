import CoreGraphics
import Foundation
import ZynclaveShip

/// Events for server-driven cards and popups. Each carries the definition id
/// and the Ship release it came from. Event names and parameters are what the
/// dashboards read; they must not change.
@MainActor
struct ShipAnalytics {
    enum TapTarget: String {
        /// The card itself (its `tap` action).
        case card
        /// A button or app tile inside the card.
        case component
    }

    enum PopupAction: String {
        /// A button or app tile inside the popup.
        case component
        /// Its close button, or a tap outside it.
        case close
    }

    let sink: ShipAnalyticsSink?
    /// The release the definitions on screen came from. Definitions exist only
    /// inside a release, so "none" means a bug, not a real value.
    let releaseID: () -> String?

    private func send(_ name: String, _ id: String, _ extra: [String: String] = [:]) {
        sink?.log(name, ["definition_id": id, "release_id": releaseID() ?? "none"].merging(extra) { $1 })
    }

    func impression(_ id: String) { send("sdui_impression", id) }
    func tap(_ id: String, target: TapTarget) { send("sdui_tap", id, ["target": target.rawValue]) }
    func dismiss(_ id: String) { send("sdui_dismiss", id) }
    func popupShown(_ id: String) { send("popup_shown", id) }
    func popupAction(_ id: String, action: PopupAction) { send("popup_action", id, ["action": action.rawValue]) }
}

/// When a feed card counts as seen: at least half of it on screen for a full
/// second, once per feed load. Pure, so the rule is testable without a scroll
/// view; the host feeds it visible fractions and a clock, then reports the
/// ids it returns through `ShipCards.reportImpression(_:)`.
public struct SDUIImpressionTracker: Sendable {
    public static let minimumFraction: CGFloat = 0.5
    public static let minimumDuration: TimeInterval = 1

    /// When each card currently at least half visible became so.
    private var visibleSince: [String: Date] = [:]
    /// Cards already counted in this feed load.
    private var counted: Set<String> = []

    public init() {}

    /// Feed it every card on screen now (id -> visible fraction); returns the
    /// ids that just qualified, in id order. A card missing from `fractions`
    /// is off screen, and its second starts over next time.
    public mutating func update(fractions: [String: CGFloat], now: Date) -> [String] {
        var qualified: [String] = []
        for id in Array(visibleSince.keys) where (fractions[id] ?? 0) < Self.minimumFraction {
            visibleSince[id] = nil
        }
        for (id, fraction) in fractions where fraction >= Self.minimumFraction && !counted.contains(id) {
            let since = visibleSince[id] ?? now
            visibleSince[id] = since
            if now.timeIntervalSince(since) >= Self.minimumDuration {
                qualified.append(id)
            }
        }
        for id in qualified {
            counted.insert(id)
            visibleSince[id] = nil
        }
        return qualified.sorted()
    }

    /// The moment the earliest pending card would qualify if it stays put.
    /// The host re-checks then, since a card that sits still sends no scroll
    /// events.
    public func nextDeadline() -> Date? {
        visibleSince.values.min().map { $0.addingTimeInterval(Self.minimumDuration) }
    }

    /// A new feed load: every card may count again.
    public mutating func reset() {
        visibleSince = [:]
        counted = []
    }
}
