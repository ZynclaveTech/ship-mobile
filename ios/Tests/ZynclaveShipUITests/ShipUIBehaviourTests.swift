import Combine
import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import Testing
import ZynclaveShip
@testable import ZynclaveShipUI
#if canImport(AppKit)
import AppKit
#endif

/// Remembers the hook a custom component was handed, so a test can tap it.
final class HookBox: @unchecked Sendable {
    var hook: (() -> Void)?
}

/// An app's custom component: it reads the public hook, as the docs say to.
private struct ProbeTile: View {
    let box: HookBox
    @Environment(\.shipComponentWillAct) private var willAct

    var body: some View {
        box.hook = willAct
        return Text("Open room")
    }
}

@MainActor @Suite struct ShipUIBehaviourTests {
    // MARK: The component hook

    #if canImport(AppKit)
    @Test func aCustomComponentThatCallsTheHookClosesThePopupAndLogsAComponentAction() async throws {
        let box = HookBox()
        let sink = RecordingSink()
        var closed: [String] = []
        var o = ShipUIOptions(deeplinkScheme: "shipsample")
        o.analytics = sink
        o.onPopupClosed = { closed.append($0.id) }
        o.components = ["tile": { _ in AnyView(ProbeTile(box: box)) }]
        let d = freshDefaults()
        d.set(1, forKey: "sdui.popups.launches")
        let body: [String: Any] = ["type": "vstack", "children": [["type": "tile", "room": "r1"]]]
        let p = ShipPopups(client: client(root: scratch(), files: ["sdui/popups/hi.json": popupJSON(id: "hi", extra: ["body": body])]),
                           options: o, defaults: d, observesLifecycle: false)
        await p.arm(now: .now)
        let popup = try #require(p.armed)

        let host = NSHostingView(rootView: ShipPopupView(popup: popup, popups: p))
        let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 400, height: 800), styleMask: [.borderless],
                              backing: .buffered, defer: false)
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        for _ in 0..<50 where box.hook == nil {
            try await Task.sleep(for: .milliseconds(20))
            host.layoutSubtreeIfNeeded()
        }
        let hook = try #require(box.hook, "the custom component was handed the hook")

        hook()
        #expect(p.armed == nil, "the hook closed the popup")
        #expect(closed == ["hi"])
        let actions = sink.events.filter { $0.0 == "popup_action" }
        #expect(actions.count == 1)
        #expect(actions.first?.1["action"] == "component")
        #expect(actions.first?.1["definition_id"] == "hi")
    }
    #endif

    @Test func outsideAPopupOrCardThereIsNoHook() {
        #expect(EnvironmentValues().shipComponentWillAct == nil)
    }

    // MARK: Deep-link scheme

    @Test func deeplinkSchemesFollowRFC3986AndAreNotWeb() {
        for ok in ["shipsample", "myapp", "my-app", "com.example.app", "x+y", "a1", "MyApp"] {
            #expect(ShipUIOptions.isValidDeeplinkScheme(ok), "\(ok)")
        }
        for bad in ["", "1app", "-app", "my app", "my_app", "myapp://", "myapp:", "http", "https", "HTTPS", "é"] {
            #expect(!ShipUIOptions.isValidDeeplinkScheme(bad), "\(bad)")
        }
    }

    // Exit tests need a separate process, which iOS test runners cannot spawn.
    #if os(macOS)
    @Test func aWebSchemeStopsTheApp() async {
        await #expect(processExitsWith: .failure) {
            _ = ShipUIOptions(deeplinkScheme: "https")
        }
        await #expect(processExitsWith: .failure) {
            _ = ShipUIOptions(deeplinkScheme: "")
        }
    }
    #endif

    // MARK: Cards

    @Test func refreshTellsObserversToAskAgain() {
        let cards = ShipCards(client: client(root: scratch(), files: ["sdui/cards/c.json": cardJSON(id: "c")]),
                              options: ShipUIOptions(deeplinkScheme: "shipsample"), defaults: freshDefaults())
        var fired = 0
        let sub = cards.objectWillChange.sink { fired += 1 }
        cards.refresh()
        #expect(fired == 1)
        sub.cancel()
    }

    // MARK: openWeb

    @Test func openWebHookReceivesTheURLAndTheDefaultOpenerIsSkipped() {
        var hooked: [URL] = []
        var defaulted: [URL] = []
        var options = ShipUIOptions(deeplinkScheme: "shipsample")
        options.openWeb = { hooked.append($0) }
        let url = URL(string: "https://example.com/a")!
        SDUIActions.perform(.web(url), options: options, openURL: { defaulted.append($0) }, dismiss: {})
        #expect(hooked == [url])
        #expect(defaulted.isEmpty)
    }

    @Test func webActionUsesTheDefaultOpenerWhenNoHookIsSet() {
        var defaulted: [URL] = []
        let options = ShipUIOptions(deeplinkScheme: "shipsample")
        #expect(options.openWeb == nil)
        let url = URL(string: "https://example.com/b")!
        SDUIActions.perform(.web(url), options: options, openURL: { defaulted.append($0) }, dismiss: {})
        #expect(defaulted == [url])
    }

    @Test func openWebCanBePassedToTheInitialiser() {
        var hooked: [URL] = []
        let options = ShipUIOptions(deeplinkScheme: "shipsample", openWeb: { hooked.append($0) })
        let url = URL(string: "https://example.com/c")!
        SDUIActions.perform(.web(url), options: options, openURL: { _ in }, dismiss: {})
        #expect(hooked == [url])
    }

    // MARK: Dismissals across instances

    @Test func dismissingInOneInstanceUpdatesAnotherSharingTheDefaults() {
        let root = scratch()
        let c = client(root: root, files: ["sdui/cards/c.json": cardJSON(id: "c")])
        let defaults = freshDefaults()
        let options = ShipUIOptions(deeplinkScheme: "shipsample")
        let a = ShipCards(client: c, options: options, defaults: defaults)
        let b = ShipCards(client: c, options: options, defaults: defaults)
        #expect(b.cards(tab: "discover").map(\.id) == ["c"])
        var fired = 0
        let sub = b.objectWillChange.sink { fired += 1 }
        a.dismiss("c")
        #expect(fired == 1)
        #expect(b.cards(tab: "discover").isEmpty)
        sub.cancel()
    }

    @Test func aDismissDoesNotPublishTwiceOnTheInstanceThatMadeIt() {
        let c = client(root: scratch(), files: ["sdui/cards/c.json": cardJSON(id: "c")])
        let a = ShipCards(client: c, options: ShipUIOptions(deeplinkScheme: "shipsample"), defaults: freshDefaults())
        var fired = 0
        let sub = a.objectWillChange.sink { fired += 1 }
        a.dismiss("c")
        #expect(fired == 1)
        sub.cancel()
    }

    // MARK: Images

    private func png(width: Int, height: Int) -> URL {
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(CGColor(red: 0, green: 0.5, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let url = scratch().appending(path: "img.png")
        let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
        _ = CGImageDestinationFinalize(dest)
        return url
    }

    @Test func anImageOverTheSizeCapIsNeverDecoded() {
        let cache = ShipImageCache()
        for url in [png(width: ShipImage.maxSide + 1, height: 2), png(width: 2, height: ShipImage.maxSide + 1)] {
            #expect(ShipImage.size(url) == nil)
            #expect(ShipImage.load(url) == nil)
            #expect(cache.size(release: "r1", asset: "images/huge.png", url: url) == nil)
            #expect(cache.load(release: "r1", asset: "images/huge-\(url.path.hashValue).png", url: url, maxWidth: 100) == nil)
        }
        let edge = png(width: ShipImage.maxSide, height: 2)
        #expect(ShipImage.load(edge)?.width == ShipImage.maxSide, "the cap itself is allowed")
    }

    @Test func imagesAreDownsampledToTheDrawnWidthAndNeverUpscaled() throws {
        let url = png(width: 2000, height: 100)
        #expect(try #require(ShipImage.load(url, maxWidth: 500)).width == 500)
        #expect(try #require(ShipImage.load(url, maxWidth: 4000)).width == 2000)
    }

    @Test func aSecondLoadHitsTheCache() throws {
        let cache = ShipImageCache()
        let url = png(width: 800, height: 400)
        let first = try #require(cache.load(release: "r1", asset: "images/a.png", url: url, maxWidth: 400))
        let again = try #require(cache.load(release: "r1", asset: "images/a.png", url: url, maxWidth: 300))
        #expect(first === again)
        #expect(cache.decodes == 1)
        #expect(cache.cached(release: "r1", asset: "images/a.png", maxWidth: 400) === first)
        #expect(cache.cached(release: "r2", asset: "images/a.png", maxWidth: 400) == nil, "another release is another image")
        _ = cache.load(release: "r1", asset: "images/a.png", url: url, maxWidth: 800)
        #expect(cache.decodes == 2, "wider than the decode: decoded again")
    }

    // MARK: Language

    private func bundle(localizations: [String]) -> Bundle {
        let dir = scratch().appending(path: "Lang.bundle")
        for l in localizations {
            write(Data("\"k\" = \"v\";".utf8), to: dir.appending(path: "\(l).lproj/Localizable.strings"))
        }
        write(Data("x".utf8), to: dir.appending(path: "Contents.txt"))
        return Bundle(url: dir)!
    }

    @Test func theDefaultLanguageIsTheOneTheAppRunsIn() {
        #expect(ShipUIOptions.appLanguage(bundle: bundle(localizations: ["fr"]), preferredLanguages: ["de-DE"]) == "fr")
        #expect(ShipUIOptions.appLanguage(bundle: bundle(localizations: ["pt-BR"]), preferredLanguages: ["de-DE"]) == "pt")
    }

    @Test func withNoLocalizationsItFallsBackToTheDevicesLanguage() {
        #expect(ShipUIOptions.appLanguage(bundle: bundle(localizations: []), preferredLanguages: ["hi-IN", "en"]) == "hi")
        #expect(ShipUIOptions.appLanguage(bundle: bundle(localizations: ["Base"]), preferredLanguages: ["hi-IN"]) == "hi")
        #expect(ShipUIOptions.appLanguage(bundle: bundle(localizations: []), preferredLanguages: []) == "en")
    }
}
