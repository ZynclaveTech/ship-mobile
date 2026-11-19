import CryptoKit
import Foundation
import os

/// Fetches bytes over HTTP. Injected so tests run without a network.
public protocol ShipTransport: Sendable {
    /// Returns the body and status. Must throw `ShipError.tooLarge` instead of
    /// buffering more than `limit` bytes.
    func get(_ url: URL, limit: Int64) async throws -> (Data, Int)
}

extension URLSession: ShipTransport {
    public func get(_ url: URL, limit: Int64) async throws -> (Data, Int) {
        var req = URLRequest(url: url)
        req.timeoutInterval = 20
        let (bytes, resp) = try await self.bytes(for: req)
        if resp.expectedContentLength > limit { throw ShipError.tooLarge }
        var data = Data()
        for try await b in bytes {
            data.append(b)
            if data.count > limit { throw ShipError.tooLarge }
        }
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }
}

public struct ShipConfig: Sendable {
    public var cdn: URL
    public var app: String
    public var channel: String
    /// Identifies what the installed binary can run, e.g. "ios-4.12.0". A release
    /// reaches only builds with the same runtime.
    public var runtime: String
    public var pinned: [PinnedKey]
    /// Stable per-install value in 0...99 that decides partial rollouts.
    public var bucket: Int
    /// Refuse releases whose assets add up to more than this.
    public var maxReleaseBytes: Int64 = 50 << 20

    public init(cdn: URL, app: String, channel: String, runtime: String, pinned: [PinnedKey], bucket: Int) {
        self.cdn = cdn
        self.app = app
        self.channel = channel
        self.runtime = runtime
        self.pinned = pinned
        self.bucket = bucket
    }

    /// A random bucket chosen once per install and kept in `defaults`.
    public static func stableBucket(_ defaults: UserDefaults = .standard, key: String = "zynclave.ship.bucket") -> Int {
        if let b = defaults.object(forKey: key) as? Int, (0...99).contains(b) { return b }
        let b = Int.random(in: 0...99)
        defaults.set(b, forKey: key)
        return b
    }
}

/// What is applied on this device. Persisted inside the content directory so
/// content and state swap together in one rename.
public struct AppliedState: Codable, Sendable, Equatable {
    /// What this content was applied for. A different app, channel or runtime
    /// (e.g. after an App Store update) means it no longer applies and is dropped.
    public let app: String
    public let channel: String
    public let runtime: String
    public let releaseID: String
    public let seq: Int64
    public let revertToEmbedded: Bool
    public let assets: [String]
}

public enum SyncResult: Sendable, Equatable {
    /// The channel has nothing for this device; whatever is applied stays.
    case noUpdate
    case upToDate(seq: Int64)
    case applied(AppliedState)
}

/// Read-only view of applied content, safe to call from any thread (e.g. a
/// `Bundle` localization lookup on the main thread).
public final class ShipContent: Sendable {
    private struct Snapshot {
        var state: AppliedState?
        var dir: URL?
        var strings: [String: [String: String]] = [:]
    }

    private let lock = OSAllocatedUnfairLock(initialState: Snapshot())

    /// Parsed flags and theme of this content's applied release. Per instance,
    /// so two contents never read each other's values, whatever their release ids.
    let flagsCache = ReleaseCache<ShipFlags.FlagBox>(empty: ShipFlags.FlagBox([:]))
    let themeCache = ReleaseCache<[String: ShipTheme.Pair]>(empty: [:])

    public var state: AppliedState? { lock.withLock { $0.state } }

    /// File URL of an applied asset, or nil when no release (or a revert) is applied.
    public func url(for asset: String) -> URL? {
        lock.withLock { snap in
            guard let dir = snap.dir, let s = snap.state, s.assets.contains(asset) else { return nil }
            return dir.appending(path: asset)
        }
    }

    /// The OTA value for `key` in `strings/<locale>.json` (a flat JSON object), if any.
    public func string(_ key: String, locale: String) -> String? {
        lock.withLock { snap in
            if let table = snap.strings[locale] { return table[key] }
            guard let dir = snap.dir, let s = snap.state, s.assets.contains("strings/\(locale).json") else {
                snap.strings[locale] = [:]
                return nil
            }
            let file = dir.appending(path: "strings/\(locale).json")
            let table = (try? Data(contentsOf: file))
                .flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
            snap.strings[locale] = table
            return table[key]
        }
    }

    func load(dir: URL, state: AppliedState?) {
        lock.withLock { $0 = Snapshot(state: state, dir: state == nil ? nil : dir) }
    }
}

/// Keeps one app/channel's OTA content in sync with the Ship CDN.
public actor ShipClient {
    public nonisolated let content = ShipContent()
    private let config: ShipConfig
    private let transport: ShipTransport
    private let root: URL
    private var current: URL { root.appending(path: "current") }

    /// - Parameter root: directory owned by this client (created if missing).
    public init(config: ShipConfig, root: URL, transport: ShipTransport = URLSession.shared) {
        self.config = config
        self.transport = transport
        self.root = root
        let cur = root.appending(path: "current")
        var state = Self.readState(cur)
        if let s = state, s.app != config.app || s.channel != config.channel || s.runtime != config.runtime {
            try? FileManager.default.removeItem(at: cur)
            state = nil
        }
        content.load(dir: cur, state: state)
    }

    static let manifestLimit: Int64 = 1 << 20

    public var applied: AppliedState? { content.state }

    /// Fetches the manifest for this device and applies it if it is new.
    /// On any error the applied content is left exactly as it was.
    @discardableResult
    public func sync() async throws -> SyncResult {
        var q = URLComponents(url: config.cdn.appending(path: "v1/manifest"), resolvingAgainstBaseURL: false)!
        q.queryItems = [
            .init(name: "app", value: config.app), .init(name: "platform", value: "ios"),
            .init(name: "channel", value: config.channel), .init(name: "runtime", value: config.runtime),
            .init(name: "bucket", value: String(config.bucket)),
        ]
        let (body, status) = try await transport.get(q.url!, limit: Self.manifestLimit)
        if status == 204 { return .noUpdate }
        guard status == 200 else { throw ShipError.http(status) }

        let have = content.state
        let m = try ManifestVerifier.verify(body, pinned: config.pinned, expect: Expectation(
            app: config.app, runtime: config.runtime, channel: config.channel, minSeq: have?.seq ?? 0))
        if let have, m.seq == have.seq { return .upToDate(seq: m.seq) }
        guard m.assets.reduce(0, { $0 + $1.size }) <= config.maxReleaseBytes else {
            throw ShipError.malformed("release larger than \(config.maxReleaseBytes) bytes")
        }

        let fm = FileManager.default
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        let incoming = root.appending(path: "incoming-\(UUID().uuidString)")
        try fm.createDirectory(at: incoming, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: incoming) }

        for a in m.assets {
            let (data, st) = try await transport.get(config.cdn.appending(path: "blobs/\(config.app)/\(a.sha256)"), limit: a.size)
            guard st == 200 else { throw ShipError.http(st) }
            let sum = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard Int64(data.count) == a.size, sum == a.sha256 else { throw ShipError.assetMismatch(a.name) }
            let dest = incoming.appending(path: a.name)
            try fm.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: dest)
        }
        let state = AppliedState(app: config.app, channel: config.channel, runtime: config.runtime, releaseID: m.releaseID, seq: m.seq, revertToEmbedded: m.revertToEmbedded,
                                 assets: m.assets.map(\.name))
        try JSONEncoder().encode(state).write(to: incoming.appending(path: Self.stateFile))

        // Actor calls interleave at every await: another sync may have applied
        // the same or a newer release meanwhile. Never swap backwards.
        if let now = content.state, m.seq <= now.seq { return .upToDate(seq: now.seq) }
        // One rename swaps content and state together; a crash leaves either the old or the new release.
        if fm.fileExists(atPath: current.path) {
            _ = try fm.replaceItemAt(current, withItemAt: incoming)
        } else {
            try fm.moveItem(at: incoming, to: current)
        }
        content.load(dir: current, state: state)
        return .applied(state)
    }

    static let stateFile = ".ship-state.json"

    static func readState(_ dir: URL) -> AppliedState? {
        guard let data = try? Data(contentsOf: dir.appending(path: stateFile)) else { return nil }
        return try? JSONDecoder().decode(AppliedState.self, from: data)
    }
}
