import XCTest
@testable import ZynclaveShipUI

/// Server-driven cards and popups are all-or-nothing: anything unknown,
/// oversized or malformed drops the whole definition. These pin that, plus
/// placement, selection and the custom-node registry.
final class SDUIParseTests: XCTestCase {
    private let assets: Set<String> = ["images/sdui/banner.png", "lottie/hello.json"]

    private func card(_ overrides: [String: Any] = [:], body: Any? = nil) -> Data {
        var obj: [String: Any] = [
            "schema": 1, "id": "spring-promo", "audience": "all",
            "placement": ["tabs": ["discover"], "position": 3],
            "body": body ?? ["type": "vstack", "children": [
                ["type": "image", "src": "images/sdui/banner.png", "aspect": 2],
                ["type": "text", "key": "promo_title", "fallback": "Spring", "style": "title"],
                ["type": "button", "label": ["fallback": "Open"], "action": ["type": "deeplink", "url": "shipsample://rewards"]],
            ]],
        ]
        for (k, v) in overrides { obj[k] = v }
        return try! JSONSerialization.data(withJSONObject: obj)
    }

    private func parse(_ data: Data, scheme: String = "shipsample", custom: Set<String> = []) throws -> SDUI.Card {
        try SDUI.parseCard(data, scheme: scheme, customTypes: custom, assetExists: assets.contains)
    }

    private func popup(_ overrides: [String: Any] = [:], body: Any? = nil) -> Data {
        var obj: [String: Any] = [
            "schema": 1, "id": "spring-wishes", "audience": "all", "priority": 5,
            "body": body ?? ["type": "vstack", "padding": 24, "children": [
                ["type": "lottie", "src": "lottie/hello.json", "height": 200],
                ["type": "text", "fallback": "Hello!", "style": "title"],
                ["type": "button", "label": ["fallback": "Go"],
                 "action": ["type": "deeplink", "url": "shipsample://rewards"]],
            ]],
        ]
        for (k, v) in overrides { obj[k] = v }
        return try! JSONSerialization.data(withJSONObject: obj)
    }

    private func parsePopup(_ d: Data, custom: Set<String> = []) throws -> SDUI.Popup {
        try SDUI.parsePopup(d, scheme: "shipsample", customTypes: custom, assetExists: assets.contains)
    }

    // MARK: Cards

    func testValidCardParses() throws {
        let c = try parse(card())
        XCTAssertEqual(c.id, "spring-promo")
        XCTAssertEqual(c.position, 3)
        XCTAssertEqual(c.tabs, ["discover"])
        guard case .stack(_, _, _, _, _, let children) = c.body else { return XCTFail() }
        XCTAssertEqual(children.count, 3)
    }

    func testUnknownComponentDropsWholeCard() {
        XCTAssertThrowsError(try parse(card(body: ["type": "vstack", "children": [
            ["type": "text", "fallback": "ok"], ["type": "video", "src": "x"]]])))
    }

    func testAudienceIsAllOrTargeted() throws {
        XCTAssertFalse(try parse(card()).targeted)
        XCTAssertTrue(try parse(card(["audience": "targeted"])).targeted)
        XCTAssertThrowsError(try parse(card(["audience": "everyone"])))
        XCTAssertThrowsError(try parse(card(["audience": NSNull()])))
    }

    func testTargetedCardShowsOnlyWhenEligible() throws {
        let open = try parse(card(["id": "for-all"]))
        let targeted = try parse(card(["id": "for-some", "audience": "targeted",
                                       "placement": ["tabs": ["discover"], "position": 1, "priority": 9]]))
        let now = Date()
        XCTAssertEqual(SDUI.cards([open, targeted], tab: "discover", now: now, dismissed: [], eligible: [])
            .map(\.id), ["for-all"])
        XCTAssertEqual(SDUI.cards([open, targeted], tab: "discover", now: now, dismissed: [], eligible: ["for-some"])
            .map(\.id), ["for-some", "for-all"])
        XCTAssertEqual(SDUI.cards([targeted], tab: "discover", now: now, dismissed: ["for-some"], eligible: ["for-some"])
            .map(\.id), [])
    }

    func testNewerSchemaOrRendererIsSkipped() {
        XCTAssertThrowsError(try parse(card(["schema": 2])))
        XCTAssertThrowsError(try parse(card(["minRenderer": 99])))
        XCTAssertNoThrow(try parse(card(["minRenderer": 1])))
    }

    func testActionsAreRestricted() {
        func withAction(_ a: [String: Any]) -> Data {
            card(body: ["type": "button", "label": ["fallback": "x"], "action": a])
        }
        XCTAssertNoThrow(try parse(withAction(["type": "web", "url": "https://example.com/spring"])))
        XCTAssertNoThrow(try parse(withAction(["type": "dismiss"])))
        XCTAssertThrowsError(try parse(withAction(["type": "web", "url": "http://example.com"])))
        XCTAssertThrowsError(try parse(withAction(["type": "deeplink", "url": "https://evil.example"])))
        XCTAssertThrowsError(try parse(withAction(["type": "web", "url": "javascript:alert(1)"])))
        XCTAssertThrowsError(try parse(withAction(["type": "share"])))
    }

    func testDeeplinkWrongSchemeIsInvalid() {
        let wrong = card(body: ["type": "button", "label": ["fallback": "x"],
                                "action": ["type": "deeplink", "url": "otherapp://x"]])
        XCTAssertThrowsError(try parse(wrong, scheme: "shipsample")) { error in
            XCTAssertTrue(error is SDUI.Invalid)
        }
        XCTAssertNoThrow(try parse(wrong, scheme: "otherapp"))
        let noHost = card(body: ["type": "button", "label": ["fallback": "x"],
                                 "action": ["type": "deeplink", "url": "shipsample:"]])
        XCTAssertThrowsError(try parse(noHost))
    }

    func testImagesOnlyFromTheRelease() {
        XCTAssertThrowsError(try parse(card(body: ["type": "image", "src": "https://cdn.example/x.png"])))
        XCTAssertThrowsError(try parse(card(body: ["type": "image", "src": "images/missing.png"])))
        XCTAssertThrowsError(try parse(card(body: ["type": "image", "src": "images/../config/flags.json"])))
    }

    func testColorsMustBeTokens() {
        XCTAssertNoThrow(try parse(card(body: ["type": "text", "fallback": "x", "color": ["token": "primary"]])))
        XCTAssertThrowsError(try parse(card(body: ["type": "text", "fallback": "x", "color": "#FF0000"])))
        XCTAssertThrowsError(try parse(card(body: ["type": "text", "fallback": "x", "color": ["token": "neonPink"]])))
    }

    func testSizeLimits() {
        let many = (0..<45).map { _ in ["type": "spacer"] }
        XCTAssertThrowsError(try parse(card(body: ["type": "vstack", "children": many])))
        var deep: [String: Any] = ["type": "text", "fallback": "x"]
        for _ in 0..<7 { deep = ["type": "vstack", "children": [deep]] }
        XCTAssertThrowsError(try parse(card(body: deep)))
        XCTAssertThrowsError(try parse(card(body: ["type": "text", "fallback": String(repeating: "a", count: 501)])))
    }

    func testBadIDsAndPlacement() {
        XCTAssertThrowsError(try parse(card(["id": "Has Spaces"])))
        XCTAssertThrowsError(try parse(card(["placement": ["tabs": ["explore"], "position": 3]])))
        XCTAssertThrowsError(try parse(card(["placement": ["tabs": ["discover"], "position": -1]])))
    }

    func testSelectionScheduleDismissPriorityAndCap() throws {
        let now = Date()
        func make(_ id: String, priority: Int, start: TimeInterval? = nil, end: TimeInterval? = nil) throws -> SDUI.Card {
            var o: [String: Any] = ["id": id, "placement": ["tabs": ["discover"], "position": 1, "priority": priority]]
            let f = ISO8601DateFormatter()
            if let start { o["start"] = f.string(from: now.addingTimeInterval(start)) }
            if let end { o["end"] = f.string(from: now.addingTimeInterval(end)) }
            return try parse(card(o))
        }
        let all = [try make("a", priority: 1), try make("b", priority: 5), try make("c", priority: 3),
                   try make("future", priority: 9, start: 3600), try make("expired", priority: 9, end: -3600),
                   try make("gone", priority: 9)]
        let shown = SDUI.cards(all, tab: "discover", now: now, dismissed: ["gone"], eligible: []).map(\.id)
        XCTAssertEqual(shown, ["b", "c"])
        XCTAssertTrue(SDUI.cards(all, tab: "following", now: now, dismissed: [], eligible: []).isEmpty)
    }

    func testDuplicateIDsAreCollapsed() throws {
        let a = try parse(card())
        let b = try parse(card(["placement": ["tabs": ["discover"], "position": 1, "priority": 99]]))
        XCTAssertEqual(SDUI.cards([a, b], tab: "discover", now: Date(), dismissed: [], eligible: []).count, 1)
    }

    // MARK: Custom nodes

    func testUnregisteredCustomTypeDropsDefinition() {
        let data = card(body: ["type": "room", "id": "abc"])
        XCTAssertThrowsError(try parse(data, custom: [])) { error in
            XCTAssertTrue(error is SDUI.Invalid)
        }
        XCTAssertThrowsError(try parse(card(body: ["type": "vstack", "children": [
            ["type": "text", "fallback": "ok"], ["type": "room", "id": "abc"]]]), custom: []))
        XCTAssertThrowsError(try parsePopup(popup(body: ["type": "room", "id": "abc"]), custom: []))
    }

    func testRegisteredCustomTypeParses() throws {
        let c = try parse(card(body: ["type": "room", "id": "abc", "showJoin": false, "n": 2.5,
                                      "tags": ["a", NSNull()], "meta": ["k": "v"]]), custom: ["room"])
        XCTAssertEqual(c.body, .custom(SDUI.CustomNode(type: "room", fields: [
            "id": .string("abc"), "showJoin": .bool(false), "n": .number(2.5),
            "tags": .array([.string("a"), .null]), "meta": .object(["k": .string("v")]),
        ])))
        guard case .custom(let node) = c.body else { return XCTFail() }
        XCTAssertEqual(node.type, "room")
        XCTAssertNil(node.fields["type"])
        let p = try parsePopup(popup(body: ["type": "room", "id": "abc"]), custom: ["room"])
        guard case .custom = p.body else { return XCTFail() }
    }

    func testCustomTypeCannotShadowABuiltIn() {
        // "text" without its fallback is still invalid even if registered.
        XCTAssertThrowsError(try parse(card(body: ["type": "text"]), custom: ["text"]))
    }

    func testCustomNodesCountTowardTheNodeLimit() {
        let many = (0..<45).map { _ in ["type": "room", "id": "a"] }
        XCTAssertThrowsError(try parse(card(body: ["type": "vstack", "children": many]), custom: ["room"]))
    }

    // MARK: Popups

    func testValidPopupWithLottieParses() throws {
        let p = try parsePopup(popup())
        XCTAssertEqual(p.id, "spring-wishes")
        XCTAssertTrue(p.closeButton)
        guard case .stack(_, _, _, _, _, let children) = p.body,
              case .lottie(let src, let height, let loop) = children[0] else { return XCTFail() }
        XCTAssertEqual(src, "lottie/hello.json")
        XCTAssertEqual(height, 200)
        XCTAssertTrue(loop)
    }

    func testLottieOnlyFromTheRelease() {
        XCTAssertThrowsError(try parsePopup(popup(body: ["type": "lottie", "src": "lottie/missing.json"])))
        XCTAssertThrowsError(try parsePopup(popup(body: ["type": "lottie", "src": "https://cdn.example/a.json"])))
        XCTAssertThrowsError(try parsePopup(popup(body: ["type": "lottie", "src": "images/sdui/banner.png"])))
        XCTAssertThrowsError(try parsePopup(popup(body: ["type": "lottie", "src": "lottie/hello.json", "height": 2000])))
    }

    func testPopupSameStrictnessAsCards() {
        XCTAssertThrowsError(try parsePopup(popup(["audience": "everyone"])))
        XCTAssertThrowsError(try parsePopup(popup(["schema": 2])))
        XCTAssertThrowsError(try parsePopup(popup(body: ["type": "carousel"])))
        XCTAssertThrowsError(try parsePopup(popup(["start": "next tuesday"])))
    }

    func testPopupOncePerIDPriorityAndSchedule() throws {
        let now = Date()
        let f = ISO8601DateFormatter()
        func make(_ id: String, _ priority: Int, end: TimeInterval? = nil) throws -> SDUI.Popup {
            var o: [String: Any] = ["id": id, "priority": priority]
            if let end { o["end"] = f.string(from: now.addingTimeInterval(end)) }
            return try parsePopup(popup(o))
        }
        let all = [try make("low", 1), try make("high", 9), try make("expired", 99, end: -60)]
        XCTAssertEqual(SDUI.nextPopup(all, now: now, shown: [], eligible: []).map(\.id), "high")
        XCTAssertEqual(SDUI.nextPopup(all, now: now, shown: ["high"], eligible: []).map(\.id), "low")
        XCTAssertNil(SDUI.nextPopup(all, now: now, shown: ["high", "low"], eligible: []))
    }

    func testTargetedPopupShowsOnlyWhenEligible() throws {
        XCTAssertFalse(try parsePopup(popup()).targeted)
        let open = try parsePopup(popup(["id": "for-all", "priority": 1]))
        let targeted = try parsePopup(popup(["id": "for-some", "audience": "targeted", "priority": 9]))
        XCTAssertTrue(targeted.targeted)
        let now = Date()
        XCTAssertEqual(SDUI.nextPopup([open, targeted], now: now, shown: [], eligible: []).map(\.id), "for-all")
        XCTAssertEqual(SDUI.nextPopup([open, targeted], now: now, shown: [], eligible: ["for-some"]).map(\.id), "for-some")
        XCTAssertEqual(SDUI.eligiblePopups([targeted], now: now, shown: ["for-some"], eligible: ["for-some"]).map(\.id), [])
    }

    // MARK: Personalize

    func testPersonalize() {
        XCTAssertEqual(SDUI.personalize("Hi {first_name}!", firstName: "Ana"), "Hi Ana!")
        XCTAssertEqual(SDUI.personalize("Happy Diwali, {first_name}!", firstName: nil), "Happy Diwali!")
        XCTAssertEqual(SDUI.personalize("{first_name}, welcome", firstName: " "), "welcome")
        XCTAssertEqual(SDUI.personalize("Hey {first_name}, claim", firstName: nil), "Hey, claim")
        XCTAssertEqual(SDUI.personalize("No placeholder", firstName: "Ana"), "No placeholder")
    }
}
