import Foundation
import SwiftUI
import Testing
import ZynclaveShip
@testable import ZynclaveShipUI

@Suite struct ShipColorResolutionTests {
    // ShipTheme caches per release id process-wide, so each runtime gets its own id.
    private func runtime(theme: String, tokens: Set<String>? = nil, app: Color? = nil,
                         revert: Bool = false) -> ShipRuntime {
        var o = ShipUIOptions(deeplinkScheme: "shipsample")
        if let tokens { o.themeTokens = tokens }
        if let app { o.color = { _ in app } }
        let files = ["config/theme.json": Data(theme.utf8)]
        return ShipRuntime(options: o, content: client(root: scratch(), files: files, revert: revert,
                                    releaseID: UUID().uuidString).content)
    }

    private func rgb(_ v: UInt32) -> Color {
        Color(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255,
              blue: Double(v & 0xFF) / 255, opacity: 1)
    }

    @Test func defaultTokensOverrideSduiColour() {
        let rt = runtime(theme: ##"{"primary": "#112233"}"##, app: .red)
        #expect(rt.color("primary", dark: false) == rgb(0x112233))   // the release beats the app
        #expect(rt.color("accent", dark: false) == .red)             // untouched token: the app's
    }

    @Test func customTokenListIsHonoured() {
        let rt = runtime(theme: ##"{"brand500": "#112233", "primary": "#445566"}"##, tokens: ["brand500"])
        #expect(rt.color("brand500", dark: false) == rgb(0x112233))
        #expect(rt.color("primary", dark: false) == ShipRuntime.defaultColor("primary", dark: false))
    }

    @Test func tokenOutsideAllowlistIgnored() {
        let rt = runtime(theme: ##"{"brand500": "#112233", "background": "#000000"}"##, app: .red)
        #expect(rt.color("brand500", dark: false) == .red)
        #expect(rt.color("background", dark: false) == .red)
    }

    @Test func revertDropsTheReleaseTheme() {
        let rt = runtime(theme: ##"{"primary": "#112233"}"##, revert: true)
        #expect(rt.color("primary", dark: false) == ShipRuntime.defaultColor("primary", dark: false))
    }
}
