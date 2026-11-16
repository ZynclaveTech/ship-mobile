import CryptoKit
import Foundation

/// The signed envelope the Ship API serves verbatim.
public struct Envelope: Codable, Sendable {
    public struct Signature: Codable, Sendable {
        public let kid: String
        public let sig: Data
    }

    /// Raw manifest JSON. Signatures cover these exact bytes, never a re-encoding.
    public let payload: Data
    public let signatures: [Signature]
}

/// One content-addressed file in a release.
public struct Asset: Codable, Sendable, Equatable {
    public let name: String
    public let sha256: String
    public let size: Int64
}

/// The signed description of one release (schema 1).
public struct Manifest: Codable, Sendable, Equatable {
    public let schema: Int
    public let releaseID: String
    public let app: String
    public let platform: String
    public let runtime: String
    public let channel: String
    public let seq: Int64
    public let issuedAt: String
    public let revertToEmbedded: Bool
    public let assets: [Asset]

    enum CodingKeys: String, CodingKey {
        case schema, app, platform, runtime, channel, seq, assets
        case releaseID = "release_id"
        case issuedAt = "issued_at"
        case revertToEmbedded = "revert_to_embedded"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decode(Int.self, forKey: .schema)
        releaseID = try c.decode(String.self, forKey: .releaseID)
        app = try c.decode(String.self, forKey: .app)
        platform = try c.decode(String.self, forKey: .platform)
        runtime = try c.decode(String.self, forKey: .runtime)
        channel = try c.decode(String.self, forKey: .channel)
        seq = try c.decode(Int64.self, forKey: .seq)
        issuedAt = try c.decode(String.self, forKey: .issuedAt)
        revertToEmbedded = try c.decodeIfPresent(Bool.self, forKey: .revertToEmbedded) ?? false
        assets = try c.decodeIfPresent([Asset].self, forKey: .assets) ?? []
    }
}

/// A pinned verification key, as served by `/v1/apps/{app}/keys`.
public struct PinnedKey: Sendable {
    public let kid: String
    public let key: Curve25519.Signing.PublicKey

    /// - Parameter base64: the raw 32-byte Ed25519 public key, base64-encoded.
    public init?(kid: String, base64: String) {
        guard let raw = Data(base64Encoded: base64),
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw) else { return nil }
        self.kid = kid
        self.key = key
    }
}

/// What the asking device expects; a validly signed manifest for anything else is rejected.
public struct Expectation: Sendable {
    public var app: String
    public var platform: String
    public var runtime: String
    public var channel: String
    /// Seq of the release this device last applied (0 if none). Lower is rejected.
    public var minSeq: Int64

    public init(app: String, platform: String = "ios", runtime: String, channel: String, minSeq: Int64 = 0) {
        self.app = app
        self.platform = platform
        self.runtime = runtime
        self.channel = channel
        self.minSeq = minSeq
    }
}

/// Why a sync or a verification failed.
public enum ShipError: Error, Equatable, Sendable, LocalizedError {
    case malformed(String)
    case badSignature
    case wrongTarget
    case stale
    case unsafeAssetName(String)
    case assetMismatch(String)
    case http(Int)
    case tooLarge

    /// Short, and safe to log: no keys, no content.
    public var errorDescription: String? {
        switch self {
        case .malformed(let detail): return "Malformed: \(detail)"
        case .badSignature: return "Bad signature"
        case .wrongTarget: return "Manifest is for another app, platform, channel or runtime"
        case .stale: return "Older than the applied release"
        case .unsafeAssetName(let name): return "Unsafe asset name: \(name)"
        case .assetMismatch(let name): return "Asset does not match its hash: \(name)"
        case .http(let status): return "HTTP \(status)"
        case .tooLarge: return "Response larger than allowed"
        }
    }
}

public enum ManifestVerifier {
    /// Prefix of every signed message, so a Ship signature can't be replayed as anything else.
    static let signingDomain = Data("zynclave-ship/manifest/v1\n".utf8)

    /// The exact bytes the server's Ed25519 key signs: domain + lowercase hex SHA-256 of the payload.
    static func signingInput(_ payload: Data) -> Data {
        let hex = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
        return signingDomain + Data(hex.utf8)
    }

    /// Verifies the envelope against the pinned keys, then the manifest against `expect`.
    public static func verify(_ raw: Data, pinned: [PinnedKey], expect: Expectation) throws -> Manifest {
        let env: Envelope
        do { env = try JSONDecoder().decode(Envelope.self, from: raw) } catch {
            throw ShipError.malformed("envelope: \(error)")
        }
        let msg = signingInput(env.payload)
        let signed = env.signatures.contains { sig in
            pinned.contains { $0.kid == sig.kid && $0.key.isValidSignature(sig.sig, for: msg) }
        }
        guard signed else { throw ShipError.badSignature }

        let m: Manifest
        do { m = try JSONDecoder().decode(Manifest.self, from: env.payload) } catch {
            throw ShipError.malformed("payload: \(error)")
        }
        try validate(m)
        let runtimeOK = m.runtime == expect.runtime || (m.runtime == "*" && m.revertToEmbedded)
        guard m.app == expect.app, m.platform == expect.platform, m.channel == expect.channel, runtimeOK else {
            throw ShipError.wrongTarget
        }
        guard m.seq >= expect.minSeq else { throw ShipError.stale }
        return m
    }

    static func validate(_ m: Manifest) throws {
        guard m.schema == 1 else { throw ShipError.malformed("schema \(m.schema)") }
        guard m.seq > 0, !m.releaseID.isEmpty, !m.issuedAt.isEmpty else { throw ShipError.malformed("seq/release_id/issued_at") }
        guard !m.runtime.isEmpty, !m.channel.isEmpty, !m.app.isEmpty else { throw ShipError.malformed("app/runtime/channel") }
        guard m.platform == "ios" || m.platform == "android" else { throw ShipError.malformed("platform \(m.platform)") }
        if m.runtime == "*" && !m.revertToEmbedded { throw ShipError.malformed("runtime * on non-revert") }
        if m.revertToEmbedded && !m.assets.isEmpty { throw ShipError.malformed("revert with assets") }
        var names = Set<String>()
        for a in m.assets {
            try checkAssetName(a.name)
            guard names.insert(a.name).inserted else { throw ShipError.malformed("duplicate asset \(a.name)") }
            guard a.size >= 0, a.sha256.count == 64,
                  a.sha256.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) else {
                throw ShipError.malformed("asset \(a.name)")
            }
        }
    }

    /// Asset names become file paths on the device, so a signed-but-hostile name
    /// must never escape the content directory.
    static func checkAssetName(_ name: String) throws {
        let parts = name.split(separator: "/", omittingEmptySubsequences: false)
        let ok = !name.isEmpty && name.utf8.count <= 255 && !name.hasPrefix("/") && !name.contains("\\")
            && !name.contains("\0") && name != ShipClient.stateFile
            && parts.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
        if !ok { throw ShipError.unsafeAssetName(name) }
    }
}
