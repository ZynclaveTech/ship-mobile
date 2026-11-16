import Foundation
import SwiftUI
import Testing
import ZynclaveShip
@testable import ZynclaveShipUI

/// Which popup shows: once per id, best priority first, in schedule, targeted
/// only when the app's eligibility names it, Ship's over the app's own on the
/// same id, and only the app's own after a revert.
@MainActor @Suite struct PopupSelectionTests {
    private func options(bundle: Bundle? = nil, eligible: Set<String> = [], gate: @escaping () -> Bool = { true },
                         sink: ShipAnalyticsSink? = nil) -> ShipUIOptions {
        var o = ShipUIOptions(deeplinkScheme: "shipsample")
        o.embeddedPopups = bundle
        o.eligibility = { (cards: [], popups: eligible) }
        o.popupGate = gate
        o.analytics = sink
        return o
    }

    private func popups(files: [String: Data] = [:], revert: Bool = false, bundle: Bundle? = nil,
                        eligible: Set<String> = [], gate: @escaping () -> Bool = { true },
                        sink: ShipAnalyticsSink? = nil, defaults: UserDefaults? = nil) -> ShipPopups {
        let d = defaults ?? freshDefaults()
        // Not the first launch after install.
        if d.integer(forKey: "sdui.popups.launches") == 0 { d.set(1, forKey: "sdui.popups.launches") }
        return ShipPopups(client: client(root: scratch(), files: files, revert: revert),
                          options: options(bundle: bundle, eligible: eligible, gate: gate, sink: sink),
                          defaults: d, observesLifecycle: false)
    }

    @Test func bestPriorityFirstThenOncePerId() async {
        let p = popups(files: [
            "sdui/popups/low.json": popupJSON(id: "low", priority: 1),
            "sdui/popups/high.json": popupJSON(id: "high", priority: 9),
        ])
        #expect(p.candidates(now: .now).map(\.id) == ["high", "low"])
        await p.arm(now: .now)
        #expect(p.armed?.id == "high")
        p.markShown(p.armed!)
        p.close()
        #expect(p.candidates(now: .now).map(\.id) == ["low"])
    }

    @Test func shownMemoryPersistsAcrossLaunchesAndUsesTheSourceKey() async {
        let d = freshDefaults()
        let a = popups(files: ["sdui/popups/a.json": popupJSON(id: "a")], defaults: d)
        await a.arm(now: .now)
        a.markShown(a.armed!)
        #expect(d.stringArray(forKey: "sdui.popups.shown") == ["a"])

        let relaunched = popups(files: ["sdui/popups/a.json": popupJSON(id: "a")], defaults: d)
        #expect(relaunched.candidates(now: .now).isEmpty)
    }

    @Test func atMostOnePerSession() async {
        let p = popups(files: [
            "sdui/popups/a.json": popupJSON(id: "a", priority: 2),
            "sdui/popups/b.json": popupJSON(id: "b", priority: 1),
        ])
        await p.arm(now: .now)
        p.markShown(p.armed!)
        p.close()
        await p.arm(now: .now)
        #expect(p.armed == nil)
    }

    @Test func scheduleIsRespected() {
        let now = Date()
        let f = ISO8601DateFormatter()
        let p = popups(files: [
            "sdui/popups/live.json": popupJSON(id: "live"),
            "sdui/popups/expired.json": popupJSON(id: "expired", priority: 99,
                                                  extra: ["end": f.string(from: now.addingTimeInterval(-60))]),
            "sdui/popups/future.json": popupJSON(id: "future", priority: 99,
                                                 extra: ["start": f.string(from: now.addingTimeInterval(3600))]),
        ])
        #expect(p.candidates(now: now).map(\.id) == ["live"])
    }

    @Test func targetedOnlyWhenEligible() {
        let files = ["sdui/popups/open.json": popupJSON(id: "open", priority: 1),
                     "sdui/popups/some.json": popupJSON(id: "some", priority: 9, audience: "targeted")]
        #expect(popups(files: files).candidates(now: .now).map(\.id) == ["open"])
        #expect(popups(files: files, eligible: ["some"]).candidates(now: .now).map(\.id) == ["some", "open"])
    }

    @Test func notOnTheFirstLaunchAfterInstall() async {
        let d = freshDefaults()  // no launch recorded yet
        let p = ShipPopups(client: client(root: scratch(), files: ["sdui/popups/a.json": popupJSON(id: "a")]),
                           options: options(), defaults: d, observesLifecycle: false)
        await p.arm(now: .now)
        #expect(p.armed == nil)
        #expect(d.integer(forKey: "sdui.popups.launches") == 1)
    }

    @Test func theGateHoldsAPopupBackWithoutSpendingIt() async {
        var open = false
        let p = popups(files: ["sdui/popups/a.json": popupJSON(id: "a")], gate: { open })
        await p.arm(now: .now)
        #expect(p.armed == nil)
        #expect(p.visiblePopup() == nil)
        open = true
        await p.arm(now: .now)
        #expect(p.visiblePopup()?.id == "a")
        open = false
        #expect(p.visiblePopup() == nil, "a call or live room hides it, and it is still unspent")
    }

    // MARK: Preflight

    private func preflighted(_ check: @escaping (SDUI.Popup) async -> Bool, defaults: UserDefaults) -> ShipPopups {
        defaults.set(1, forKey: "sdui.popups.launches")
        var o = options()
        o.popupPreflight = check
        return ShipPopups(client: client(root: scratch(), files: [
            "sdui/popups/a.json": popupJSON(id: "a", priority: 2),
            "sdui/popups/b.json": popupJSON(id: "b", priority: 1),
        ]), options: o, defaults: defaults, observesLifecycle: false)
    }

    @Test func preflightFalseHoldsPopup() async {
        let d = freshDefaults()
        let p = preflighted({ _ in false }, defaults: d)
        await p.arm(now: .now)
        #expect(p.armed == nil)
        #expect(p.visiblePopup() == nil)
        #expect(d.stringArray(forKey: "sdui.popups.shown") == nil, "not marked shown")
        #expect(p.candidates(now: .now).map(\.id) == ["a", "b"], "still pending for the next sync")
    }

    @Test func aRejectedPopupDoesNotBlockTheNextOne() async {
        let p = preflighted({ $0.id != "a" }, defaults: freshDefaults())
        await p.arm(now: .now)
        #expect(p.armed?.id == "b")
    }

    @Test func preflightTrueShowsPopup() async {
        let p = preflighted({ _ in true }, defaults: freshDefaults())
        await p.arm(now: .now)
        #expect(p.visiblePopup()?.id == "a")
    }

    // MARK: Embedded

    @Test func embeddedPopupsShowAlongsideShipsAndShipWinsTheSameId() {
        let bundle = embeddedBundle(files: [
            "sdui/popups/shared.json": popupJSON(id: "shared", priority: 1),
            "sdui/popups/own.json": popupJSON(id: "own", priority: 5),
        ])
        let p = popups(files: ["sdui/popups/shared.json": popupJSON(id: "shared", priority: 3,
                                                                    extra: ["closeButton": false])],
                       bundle: bundle)
        let all = p.candidates(now: .now)
        #expect(all.map(\.id) == ["own", "shared"])
        #expect(all.first { $0.id == "shared" }?.priority == 3, "Ship's definition wins")
        #expect(all.first { $0.id == "shared" }?.closeButton == false)
    }

    @Test func revertHidesShipButKeepsEmbedded() {
        let bundle = embeddedBundle(files: ["sdui/popups/own.json": popupJSON(id: "own")])
        let files = ["sdui/popups/shipped.json": popupJSON(id: "shipped", priority: 50),
                     "sdui/cards/c.json": cardJSON(id: "c")]
        let p = popups(files: files, revert: true, bundle: bundle)
        #expect(p.candidates(now: .now).map(\.id) == ["own"])
        let cards = ShipCards(client: client(root: scratch(), files: files, revert: true),
                              options: options(bundle: bundle), defaults: freshDefaults())
        #expect(cards.cards(tab: "discover").isEmpty)
    }

    @Test func embeddedAssetsResolveFromTheBundleLayout() throws {
        let bundle = embeddedBundle(files: [
            "sdui/popups/own.json": popupJSON(id: "own", extra: ["body": ["type": "lottie", "src": "lottie/hi.json"]]),
            "lottie/hi.json": Data("{}".utf8),
        ])
        let e = ShipEmbedded(bundle: bundle, scheme: "shipsample", customTypes: [])
        #expect(e.popups.map(\.id) == ["own"])
        let url = try #require(e.url(for: "lottie/hi.json"))
        #expect(url.path.hasSuffix("ShipEmbedded/lottie/hi.json"))
        #expect(e.url(for: "lottie/none.json") == nil)

        let p = popups(bundle: bundle)
        let assetURL = p.assetURL(for: try #require(p.candidates(now: .now).first))
        #expect(assetURL("lottie/hi.json") == url)
    }

    @Test func aBrokenEmbeddedPopupIsSkippedNotFatal() {
        let bundle = embeddedBundle(files: [
            "sdui/popups/bad.json": Data("{not json".utf8),
            "sdui/popups/good.json": popupJSON(id: "good"),
        ])
        #expect(ShipEmbedded(bundle: bundle, scheme: "shipsample", customTypes: []).popups.map(\.id) == ["good"])
    }

    @Test func customTypesFromComponentsAreAcceptedByTheParser() {
        let body: [String: Any] = ["type": "vstack", "children": [["type": "tile", "id": "r1"]]]
        let files = ["sdui/popups/c.json": popupJSON(id: "c", extra: ["body": body])]
        #expect(popups(files: files).candidates(now: .now).isEmpty, "unknown component drops the popup")

        var o = options()
        o.components = ["tile": { _ in AnyView(EmptyView()) }]
        let p = ShipPopups(client: client(root: scratch(), files: files), options: o,
                           defaults: { let d = freshDefaults(); d.set(1, forKey: "sdui.popups.launches"); return d }(),
                           observesLifecycle: false)
        #expect(p.candidates(now: .now).map(\.id) == ["c"])
    }
}
