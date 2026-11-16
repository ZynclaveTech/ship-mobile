import CryptoKit
import Foundation
import Testing
@testable import ZynclaveShip

/// Signs manifests the way the server does, with a throwaway key.
struct TestSigner {
    let priv = Curve25519.Signing.PrivateKey()
    let kid = "test-key"
    var pinned: PinnedKey { PinnedKey(kid: kid, base64: priv.publicKey.rawRepresentation.base64EncodedString())! }

    func envelope(_ manifest: [String: Any]) throws -> Data {
        let payload = try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys])
        let sig = try priv.signature(for: ManifestVerifier.signingInput(payload))
        return try JSONSerialization.data(withJSONObject: [
            "payload": payload.base64EncodedString(),
            "signatures": [["kid": kid, "sig": sig.base64EncodedString()]],
        ])
    }
}

func manifest(seq: Int64 = 1, channel: String = "prod", runtime: String = "rt-1",
              assets: [[String: Any]] = [], revert: Bool = false) -> [String: Any] {
    var m: [String: Any] = ["schema": 1, "release_id": "r\(seq)", "app": "myapp", "platform": "ios",
                            "runtime": runtime, "channel": channel, "seq": seq,
                            "issued_at": "2026-11-06T00:00:00Z", "assets": assets]
    if revert { m["revert_to_embedded"] = true }
    return m
}

let expectProd = Expectation(app: "myapp", runtime: "rt-1", channel: "prod")

@Suite struct ManifestTests {
    @Test func verifiesSignedEnvelope() throws {
        let s = TestSigner()
        let asset: [String: Any] = ["name": "hello.txt", "sha256": String(repeating: "a", count: 64), "size": 6]
        let raw = try s.envelope(manifest(seq: 26, assets: [asset]))
        let m = try ManifestVerifier.verify(raw, pinned: [s.pinned], expect: expectProd)
        #expect(m.releaseID == "r26")
        #expect(m.seq == 26)
        #expect(m.assets == [Asset(name: "hello.txt", sha256: String(repeating: "a", count: 64), size: 6)])
    }

    @Test func rejectsTamperedPayload() throws {
        let s = TestSigner()
        var env = try JSONSerialization.jsonObject(with: try s.envelope(manifest())) as! [String: Any]
        var payload = Data(base64Encoded: env["payload"] as! String)!
        payload[payload.count - 5] ^= 0x01
        env["payload"] = payload.base64EncodedString()
        let raw = try JSONSerialization.data(withJSONObject: env)
        #expect(throws: ShipError.self) {
            try ManifestVerifier.verify(raw, pinned: [s.pinned], expect: expectProd)
        }
    }

    @Test func rejectsUnpinnedKeyAndWrongKid() throws {
        let s = TestSigner()
        let raw = try s.envelope(manifest())
        let other = TestSigner()
        #expect(throws: ShipError.badSignature) {
            try ManifestVerifier.verify(raw, pinned: [other.pinned], expect: expectProd)
        }
        let wrongKid = PinnedKey(kid: "other", base64: s.priv.publicKey.rawRepresentation.base64EncodedString())!
        #expect(throws: ShipError.badSignature) {
            try ManifestVerifier.verify(raw, pinned: [wrongKid], expect: expectProd)
        }
    }

    @Test func emptyPinnedKeysRejectsEverything() throws {
        let s = TestSigner()
        let raw = try s.envelope(manifest())
        #expect(throws: ShipError.badSignature) {
            try ManifestVerifier.verify(raw, pinned: [], expect: expectProd)
        }
    }

    @Test func rejectsValidSignatureForAnotherTarget() throws {
        let s = TestSigner()
        let raw = try s.envelope(manifest(channel: "staging"))
        #expect(throws: ShipError.wrongTarget) { try ManifestVerifier.verify(raw, pinned: [s.pinned], expect: expectProd) }
        let rt = try s.envelope(manifest(runtime: "rt-2"))
        #expect(throws: ShipError.wrongTarget) { try ManifestVerifier.verify(rt, pinned: [s.pinned], expect: expectProd) }
    }

    @Test func rejectsDowngradeAcceptsSameSeq() throws {
        let s = TestSigner()
        let raw = try s.envelope(manifest(seq: 5))
        var e = expectProd
        e.minSeq = 6
        #expect(throws: ShipError.stale) { try ManifestVerifier.verify(raw, pinned: [s.pinned], expect: e) }
        e.minSeq = 5
        #expect(try ManifestVerifier.verify(raw, pinned: [s.pinned], expect: e).seq == 5)
    }

    @Test func revertMatchesAnyRuntimeButOnlyWhenRevert() throws {
        let s = TestSigner()
        let revert = try s.envelope(manifest(runtime: "*", revert: true))
        #expect(try ManifestVerifier.verify(revert, pinned: [s.pinned], expect: expectProd).revertToEmbedded)
        let star = try s.envelope(manifest(runtime: "*"))
        #expect(throws: ShipError.self) { try ManifestVerifier.verify(star, pinned: [s.pinned], expect: expectProd) }
    }

    @Test(arguments: ["../etc/passwd", "/abs", "a/../b", "a//b", "./a", "a\\b", "", ".ship-state.json"])
    func rejectsUnsafeAssetNames(_ name: String) throws {
        let s = TestSigner()
        let raw = try s.envelope(manifest(assets: [["name": name, "sha256": String(repeating: "a", count: 64), "size": 1]]))
        #expect(throws: ShipError.self) { try ManifestVerifier.verify(raw, pinned: [s.pinned], expect: expectProd) }
    }
}
