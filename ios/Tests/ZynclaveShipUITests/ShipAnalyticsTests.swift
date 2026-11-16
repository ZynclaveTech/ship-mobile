import Foundation
import Testing
import ZynclaveShip
@testable import ZynclaveShipUI

final class RecordingSink: ShipAnalyticsSink, @unchecked Sendable {
    var events: [(String, [String: String])] = []
    func log(_ name: String, _ params: [String: String]) { events.append((name, params)) }
}

@MainActor @Suite struct ShipAnalyticsTests {
    @Test func eventNamesAndParametersAreTheOnesTheDashboardsRead() {
        let sink = RecordingSink()
        let a = ShipAnalytics(sink: sink, releaseID: { "rel-1" })
        a.impression("diwali")
        a.tap("diwali", target: .card)
        a.tap("diwali", target: .component)
        a.dismiss("diwali")
        a.popupShown("launch")
        a.popupAction("launch", action: .component)
        a.popupAction("launch", action: .close)
        let base = ["definition_id": "diwali", "release_id": "rel-1"]
        let launch = ["definition_id": "launch", "release_id": "rel-1"]
        let expected: [(String, [String: String])] = [
            ("sdui_impression", base),
            ("sdui_tap", base.merging(["target": "card"]) { $1 }),
            ("sdui_tap", base.merging(["target": "component"]) { $1 }),
            ("sdui_dismiss", base),
            ("popup_shown", launch),
            ("popup_action", launch.merging(["action": "component"]) { $1 }),
            ("popup_action", launch.merging(["action": "close"]) { $1 }),
        ]
        #expect(sink.events.count == expected.count)
        for (got, want) in zip(sink.events, expected) {
            #expect(got.0 == want.0 && got.1 == want.1)
        }
    }

    @Test func noReleaseReportsNone() {
        let sink = RecordingSink()
        ShipAnalytics(sink: sink, releaseID: { nil }).impression("x")
        #expect(sink.events.first?.1["release_id"] == "none")
    }

    @Test func cardsReportDismissOnceHoweverManyTaps() {
        let sink = RecordingSink()
        var o = ShipUIOptions(deeplinkScheme: "shipsample")
        o.analytics = sink
        let cards = ShipCards(client: client(root: scratch(), files: ["sdui/cards/c.json": cardJSON(id: "c")]),
                              options: o, defaults: freshDefaults())
        cards.dismiss("c")
        cards.dismiss("c")
        #expect(sink.events.filter { $0.0 == "sdui_dismiss" }.count == 1)
        #expect(cards.cards(tab: "discover").isEmpty)
    }

    @Test func popupShownIsReportedWhenMarkedShown() async {
        let sink = RecordingSink()
        var o = ShipUIOptions(deeplinkScheme: "shipsample")
        o.analytics = sink
        let d = freshDefaults()
        d.set(1, forKey: "sdui.popups.launches")
        let p = ShipPopups(client: client(root: scratch(), files: ["sdui/popups/a.json": popupJSON(id: "a")]),
                           options: o, defaults: d, observesLifecycle: false)
        await p.arm(now: .now)
        p.markShown(p.armed!)
        p.markShown(p.armed!)
        #expect(sink.events.map(\.0) == ["popup_shown"])
    }
}

@Suite struct ImpressionTrackerTests {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func countsAfterOneSecondAtLeastHalfVisible() {
        var tracker = SDUIImpressionTracker()
        #expect(tracker.update(fractions: ["a": 0.6], now: t0) == [])
        #expect(tracker.update(fractions: ["a": 0.9], now: t0.addingTimeInterval(0.99)) == [])
        #expect(tracker.update(fractions: ["a": 0.5], now: t0.addingTimeInterval(1.0)) == ["a"])
    }

    @Test func countsOncePerFeedLoad() {
        var tracker = SDUIImpressionTracker()
        _ = tracker.update(fractions: ["a": 1], now: t0)
        #expect(tracker.update(fractions: ["a": 1], now: t0.addingTimeInterval(1)) == ["a"])
        #expect(tracker.update(fractions: ["a": 1], now: t0.addingTimeInterval(5)) == [])
        tracker.reset()
        _ = tracker.update(fractions: ["a": 1], now: t0.addingTimeInterval(10))
        #expect(tracker.update(fractions: ["a": 1], now: t0.addingTimeInterval(11)) == ["a"])
    }

    @Test func dwellRestartsWhenItDropsBelowHalf() {
        var tracker = SDUIImpressionTracker()
        _ = tracker.update(fractions: ["a": 0.8], now: t0)
        _ = tracker.update(fractions: ["a": 0.49], now: t0.addingTimeInterval(0.6))
        #expect(tracker.update(fractions: ["a": 0.8], now: t0.addingTimeInterval(1.2)) == [])
        #expect(tracker.update(fractions: ["a": 0.8], now: t0.addingTimeInterval(2.2)) == ["a"])
    }

    @Test func offScreenCardsStopTheClock() {
        var tracker = SDUIImpressionTracker()
        _ = tracker.update(fractions: ["a": 1], now: t0)
        _ = tracker.update(fractions: [:], now: t0.addingTimeInterval(0.5))  // the view left the screen
        #expect(tracker.update(fractions: ["a": 1], now: t0.addingTimeInterval(1.1)) == [])
    }

    @Test func severalCardsAreTrackedApartAndReportedInOrder() {
        var tracker = SDUIImpressionTracker()
        _ = tracker.update(fractions: ["b": 1, "a": 1], now: t0)
        #expect(tracker.update(fractions: ["b": 1, "a": 1, "c": 1], now: t0.addingTimeInterval(1)) == ["a", "b"])
        #expect(tracker.update(fractions: ["c": 1], now: t0.addingTimeInterval(2)) == ["c"])
    }

    /// With no scroll events while the card sits still, the feed re-checks at
    /// this moment so the second still counts.
    @Test func nextDeadlineIsWhenTheEarliestPendingCardQualifies() {
        var tracker = SDUIImpressionTracker()
        #expect(tracker.nextDeadline() == nil)
        _ = tracker.update(fractions: ["a": 1], now: t0)
        _ = tracker.update(fractions: ["a": 1, "b": 1], now: t0.addingTimeInterval(0.4))
        #expect(tracker.nextDeadline() == t0.addingTimeInterval(1))
        _ = tracker.update(fractions: ["a": 1, "b": 1], now: t0.addingTimeInterval(1))
        #expect(tracker.nextDeadline() == t0.addingTimeInterval(1.4))
    }
}
