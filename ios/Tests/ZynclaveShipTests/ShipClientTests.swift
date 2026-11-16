import CryptoKit
import Foundation
import os
import Testing
@testable import ZynclaveShip

/// Serves canned responses by URL path; records requests.
final class FakeCDN: ShipTransport, Sendable {
    private let state = OSAllocatedUnfairLock(initialState: (routes: [String: (Data, Int)](), hits: [String]()))

    func set(_ path: String, _ body: Data, _ status: Int = 200) { state.withLock { $0.routes[path] = (body, status) } }
    func blob(_ data: Data) -> [String: Any] {
        let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        set("/blobs/myapp/\(sha)", data)
        return ["sha256": sha, "size": data.count]
    }
    var hits: [String] { state.withLock { $0.hits } }

    func get(_ url: URL, limit: Int64) async throws -> (Data, Int) {
        let r = state.withLock { s in
            s.hits.append(url.path())
            return s.routes[url.path()] ?? (Data(), 404)
        }
        if Int64(r.0.count) > limit { throw ShipError.tooLarge }
        return r
    }
}

struct ThrowingTransport: ShipTransport {
    func get(_ url: URL, limit: Int64) async throws -> (Data, Int) { throw ShipError.http(503) }
}

struct Fixture {
    let cdn = FakeCDN()
    let signer = TestSigner()
    let root = FileManager.default.temporaryDirectory.appending(path: "ship-test-\(UUID().uuidString)")

    func client(runtime: String = "rt-1") -> ShipClient {
        ShipClient(config: ShipConfig(cdn: URL(string: "https://cdn.test")!, app: "myapp", channel: "prod",
                                      runtime: runtime, pinned: [signer.pinned], bucket: 7),
                   root: root, transport: cdn)
    }

    /// Publishes a release with `strings/en.json` = `strings`.
    func publish(seq: Int64, strings: [String: String]) throws {
        let data = try JSONSerialization.data(withJSONObject: strings, options: [.sortedKeys])
        var asset = cdn.blob(data)
        asset["name"] = "strings/en.json"
        cdn.set("/v1/manifest", try signer.envelope(manifest(seq: seq, assets: [asset])))
    }
}

@Suite struct ShipClientTests {
    @Test func appliesReleaseAndServesStrings() async throws {
        let f = Fixture()
        try f.publish(seq: 1, strings: ["ota.test": "Hello from OTA"])
        let c = f.client()
        let r = try await c.sync()
        guard case .applied(let s) = r else { Issue.record("got \(r)"); return }
        #expect(s.seq == 1 && s.assets == ["strings/en.json"])
        #expect(c.content.string("ota.test", locale: "en") == "Hello from OTA")
        #expect(c.content.string("ota.test", locale: "hi") == nil)
        #expect(try await c.sync() == .upToDate(seq: 1))
    }

    @Test func survivesRelaunch() async throws {
        let f = Fixture()
        try f.publish(seq: 3, strings: ["k": "v3"])
        try await f.client().sync()
        let relaunched = f.client()
        #expect(relaunched.content.state?.seq == 3)
        #expect(relaunched.content.string("k", locale: "en") == "v3")
    }

    @Test func newReleaseReplacesOldAndClearsStringCache() async throws {
        let f = Fixture()
        let c = f.client()
        try f.publish(seq: 1, strings: ["k": "one"])
        try await c.sync()
        #expect(c.content.string("k", locale: "en") == "one")
        try f.publish(seq: 2, strings: ["k": "two"])
        try await c.sync()
        #expect(c.content.string("k", locale: "en") == "two")
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: f.root.path()).filter { $0.hasPrefix("incoming") }
        #expect(leftovers.isEmpty)
    }

    @Test func badBlobKeepsCurrentRelease() async throws {
        let f = Fixture()
        let c = f.client()
        try f.publish(seq: 1, strings: ["k": "good"])
        try await c.sync()
        // seq 2 points at a blob whose bytes don't match the signed hash
        let asset: [String: Any] = ["name": "strings/en.json", "sha256": String(repeating: "b", count: 64), "size": 4]
        f.cdn.set("/blobs/myapp/" + String(repeating: "b", count: 64), Data("evil".utf8))
        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 2, assets: [asset])))
        await #expect(throws: ShipError.assetMismatch("strings/en.json")) { try await c.sync() }
        #expect(c.content.state?.seq == 1)
        #expect(c.content.string("k", locale: "en") == "good")
    }

    @Test func downgradeIsRejected() async throws {
        let f = Fixture()
        let c = f.client()
        try f.publish(seq: 5, strings: ["k": "five"])
        try await c.sync()
        try f.publish(seq: 4, strings: ["k": "four"])
        await #expect(throws: ShipError.stale) { try await c.sync() }
        #expect(c.content.string("k", locale: "en") == "five")
    }

    @Test func revertDropsContentAndNoUpdateKeepsIt() async throws {
        let f = Fixture()
        let c = f.client()
        try f.publish(seq: 1, strings: ["k": "v"])
        try await c.sync()
        f.cdn.set("/v1/manifest", Data(), 204)
        #expect(try await c.sync() == .noUpdate)
        #expect(c.content.string("k", locale: "en") == "v")

        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 2, runtime: "*", revert: true)))
        try await c.sync()
        #expect(c.content.state?.revertToEmbedded == true)
        #expect(c.content.string("k", locale: "en") == nil)
        #expect(c.content.url(for: "strings/en.json") == nil)
    }

    @Test func appUpdateDropsContentForOldRuntime() async throws {
        let f = Fixture()
        try f.publish(seq: 9, strings: ["k": "for rt-1"])
        try await f.client().sync()
        let updated = f.client(runtime: "rt-2")
        #expect(updated.content.state == nil)
        #expect(updated.content.string("k", locale: "en") == nil)
        #expect(!FileManager.default.fileExists(atPath: f.root.appending(path: "current").path()))
    }

    @Test func oversizedBlobIsRefusedBeforeBuffering() async throws {
        let f = Fixture()
        let c = f.client()
        let sha = String(repeating: "c", count: 64)
        f.cdn.set("/blobs/myapp/\(sha)", Data(count: 1000))
        let asset: [String: Any] = ["name": "strings/en.json", "sha256": sha, "size": 10]
        f.cdn.set("/v1/manifest", try f.signer.envelope(manifest(seq: 1, assets: [asset])))
        await #expect(throws: ShipError.tooLarge) { try await c.sync() }
        #expect(c.content.state == nil)
    }

    @Test func concurrentSyncsNeverSwapBackwards() async throws {
        let f = Fixture()
        let c = f.client()
        try f.publish(seq: 1, strings: ["k": "v1"])
        async let a = c.sync()
        async let b = c.sync()
        let results = try await [a, b]
        #expect(results.filter { if case .applied = $0 { true } else { false } }.count == 1)
        #expect(c.content.state?.seq == 1)
    }

    @Test func syncFailureKeepsAppliedContent() async throws {
        let f = Fixture()
        try f.publish(seq: 1, strings: ["k": "good"])
        let c = f.client()
        try await c.sync()
        let before = c.content.state
        #expect(before?.seq == 1)

        let failing = ShipClient(config: ShipConfig(cdn: URL(string: "https://cdn.test")!, app: "myapp", channel: "prod",
                                                    runtime: "rt-1", pinned: [f.signer.pinned], bucket: 7),
                                 root: f.root, transport: ThrowingTransport())
        await #expect(throws: ShipError.self) { try await failing.sync() }
        #expect(failing.content.state == before)
        #expect(failing.content.string("k", locale: "en") == "good")
    }

    @Test func stableBucketPersists() {
        let d = UserDefaults(suiteName: "ship-test-\(UUID().uuidString)")!
        let b = ShipConfig.stableBucket(d)
        #expect((0...99).contains(b))
        #expect(ShipConfig.stableBucket(d) == b)
    }
}
