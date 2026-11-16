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
            ##"{"primary300": "#FF7A00", "accent500": {"light": "#102030", "dark": "405060"}}"##))
        let plain = ShipRGBA(r: 1, g: Double(0x7A) / 255, b: 0, a: 1)
        #expect(ShipTheme.color("primary300", dark: false, in: t) == plain)
        #expect(ShipTheme.color("primary300", dark: true, in: t) == plain)
        #expect(ShipTheme.color("accent500", dark: false, in: t) == ShipRGBA(r: 16 / 255, g: 32 / 255, b: 48 / 255, a: 1))
        #expect(ShipTheme.color("accent500", dark: true, in: t) == ShipRGBA(r: 64 / 255, g: 80 / 255, b: 96 / 255, a: 1))
    }

    @Test func badHexAndUnlistedTokensAreRejected() {
        let t = ShipTheme.parse(data(
            ##"{"primary300": "#GG0000", "primary200": "#FFF", "accent500": {"light": "#102030"}, "background": "#000000"}"##))
        #expect(t.isEmpty)
    }

    @Test func lookupsReadAppliedReleaseAndRevertMeansNone() async throws {
        let f = Fixture()
        let flags = try JSONSerialization.data(withJSONObject: ["beta": true])
        let theme = try JSONSerialization.data(withJSONObject: ["primary300": "#FF7A00"])
        var a1 = f.cdn.blob(flags); a1["name"] = "config/flags.json"
        var a2 = f.cdn.blob(theme); a2["name"] = "config/theme.json"
        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 1, assets: [a1, a2])))
        let c = f.client()
        try await c.sync()
        #expect(ShipFlags.bool("beta", in: c.content) == true)
        #expect(ShipTheme.color("primary300", dark: false, in: c.content) != nil)
        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 2, runtime: "*", revert: true)))
        try await c.sync()
        #expect(ShipFlags.bool("beta", in: c.content) == nil)
        #expect(ShipTheme.color("primary300", dark: false, in: c.content) == nil)
    }
}
