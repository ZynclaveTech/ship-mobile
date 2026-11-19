import Foundation
import Testing
@testable import ZynclaveShip

@Suite struct ShipFlagsTests {
    private func data(_ s: String) -> Data { Data(s.utf8) }

    @Test func flagsParseTypedValues() {
        let p = ShipFlags.parse(data(##"{"a": true, "n": 3, "d": 1.5, "s": "x", "z": 0}"##))
        #expect(ShipFlags.bool("a", in: p) == true)
        #expect(ShipFlags.int("n", in: p) == 3)
        #expect(ShipFlags.double("d", in: p) == 1.5)
        #expect(ShipFlags.string("s", in: p) == "x")
    }

    @Test func wrongTypeMeansNoOverride() {
        let p = ShipFlags.parse(data(##"{"a": true, "n": 3, "d": 1.5, "s": "x", "z": 0}"##))
        #expect(ShipFlags.bool("z", in: p) == nil)      // number posing as bool
        #expect(ShipFlags.int("a", in: p) == nil)       // bool posing as int
        #expect(ShipFlags.int("d", in: p) == nil)       // fractional
        #expect(ShipFlags.string("n", in: p) == nil)
        #expect(ShipFlags.bool("missing", in: p) == nil)
    }

    @Test func malformedFlagsFileIsEmpty() {
        #expect(ShipFlags.parse(data("[1,2]")).isEmpty)
        #expect(ShipFlags.parse(data("not json")).isEmpty)
    }

    @Test func themeParsesPlainAndLightDarkPairs() {
        let t = ShipTheme.parse(data(
            ##"{"primary": "#FF7A00", "accent": {"light": "#102030", "dark": "405060"}}"##))
        let plain = ShipRGBA(r: 1, g: Double(0x7A) / 255, b: 0, a: 1)
        #expect(ShipTheme.color("primary", dark: false, in: t) == plain)
        #expect(ShipTheme.color("primary", dark: true, in: t) == plain)
        #expect(ShipTheme.color("accent", dark: false, in: t) == ShipRGBA(r: 16 / 255, g: 32 / 255, b: 48 / 255, a: 1))
        #expect(ShipTheme.color("accent", dark: true, in: t) == ShipRGBA(r: 64 / 255, g: 80 / 255, b: 96 / 255, a: 1))
    }

    @Test func badHexAndUnlistedTokensAreRejected() {
        let t = ShipTheme.parse(data(
            ##"{"primary": "#GG0000", "primary200": "#FFF", "accent": {"light": "#102030"}, "background": "#000000"}"##))
        #expect(t.isEmpty)
        // A sign is not a hex digit, though UInt32(_:radix:) would read one.
        for bad in ["#+12345", "-12345", "+12345", "#12 345", "#FF7A0", "#FF7A00FF"] {
            #expect(ShipTheme.rgba(bad) == nil, "\(bad)")
        }
        #expect(ShipTheme.rgba("#ff7a00") != nil)
    }

    @Test func shipErrorsHaveShortMessages() {
        #expect(ShipError.badSignature.localizedDescription == "Bad signature")
        #expect(ShipError.http(503).localizedDescription == "HTTP 503")
        #expect(ShipError.malformed("x").localizedDescription == "Malformed: x")
    }

    @Test func tokenOutsideAllowlistIgnored() async throws {
        let f = Fixture()
        let theme = try JSONSerialization.data(
            withJSONObject: ["primary": "#FF7A00", "brand500": "#102030", "background": "#000000"])
        var a = f.cdn.blob(theme); a["name"] = "config/theme.json"
        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 1, assets: [a])))
        let c = f.client()
        try await c.sync()
        #expect(ShipTheme.color("primary", dark: false, in: c.content) != nil)
        #expect(ShipTheme.color("brand500", dark: false, in: c.content) == nil)   // not a default token
        #expect(ShipTheme.color("background", dark: false, in: c.content) == nil) // never allowed
        // Same release, another allowlist: the cached parse must not leak the first filter.
        #expect(ShipTheme.color("brand500", dark: false, in: c.content, allowed: ["brand500"])
                == ShipRGBA(r: 16 / 255, g: 32 / 255, b: 48 / 255, a: 1))
        #expect(ShipTheme.color("primary", dark: false, in: c.content, allowed: ["brand500"]) == nil)
        #expect(ShipTheme.color("primary", dark: false, in: c.content) != nil)
        #expect(ShipTheme.defaultTokens == ["primary", "accent", "surface", "surfaceVariant",
                                            "onSurface", "onSurfaceVariant", "onPrimary"])
    }

    @Test func lookupsReadAppliedReleaseAndRevertMeansNone() async throws {
        let f = Fixture()
        let flags = try JSONSerialization.data(withJSONObject: ["beta": true])
        let theme = try JSONSerialization.data(withJSONObject: ["primary": "#FF7A00"])
        var a1 = f.cdn.blob(flags); a1["name"] = "config/flags.json"
        var a2 = f.cdn.blob(theme); a2["name"] = "config/theme.json"
        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 1, assets: [a1, a2])))
        let c = f.client()
        try await c.sync()
        #expect(ShipFlags.bool("beta", in: c.content) == true)
        #expect(ShipTheme.color("primary", dark: false, in: c.content) != nil)
        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 2, runtime: "*", revert: true)))
        try await c.sync()
        #expect(ShipFlags.bool("beta", in: c.content) == nil)
        #expect(ShipTheme.color("primary", dark: false, in: c.content) == nil)
    }

    @Test func sameReleaseIDInTwoClientsDoesNotShareParsedValues() async throws {
        func synced(flag: Bool, color: String) async throws -> ShipClient {
            let f = Fixture()
            var a1 = f.cdn.blob(try JSONSerialization.data(withJSONObject: ["beta": flag])); a1["name"] = "config/flags.json"
            var a2 = f.cdn.blob(try JSONSerialization.data(withJSONObject: ["primary": color])); a2["name"] = "config/theme.json"
            f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 1, assets: [a1, a2])))  // release id "r1" in both
            let c = f.client()
            try await c.sync()
            return c
        }
        let on = try await synced(flag: true, color: "#FF0000")
        let off = try await synced(flag: false, color: "#00FF00")
        #expect(on.content.state?.releaseID == off.content.state?.releaseID)
        for _ in 0..<3 {
            #expect(ShipFlags.bool("beta", in: on.content) == true)
            #expect(ShipFlags.bool("beta", in: off.content) == false)
            #expect(ShipTheme.color("primary", dark: false, in: on.content)?.r == 1)
            #expect(ShipTheme.color("primary", dark: false, in: off.content)?.g == 1)
        }
    }
}
