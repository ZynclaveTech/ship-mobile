import Foundation
import os

/// Caches one parsed value per applied release, so repeated reads cost a
/// single locked comparison instead of a file read and JSON decode.
final class ReleaseCache<Value: Sendable>: Sendable {
    private let state: OSAllocatedUnfairLock<(releaseID: String?, value: Value)>
    private let empty: Value

    init(empty: Value) {
        self.empty = empty
        state = OSAllocatedUnfairLock(initialState: (nil, empty))
    }

    /// The value for the release applied in `content`; `empty` when there is none
    /// or the release reverts to the embedded content.
    func value(for content: ShipContent, load: () -> Value) -> Value {
        guard let release = content.state, !release.revertToEmbedded else { return empty }
        return state.withLockUnchecked { c in
            if c.releaseID == release.releaseID { return c.value }
            let v = load()
            c = (release.releaseID, v)
            return v
        }
    }
}

/// Remote-config flag values from `config/flags.json` of the applied release.
///
/// Every read returns nil when there is no override: no release, a missing or
/// undecodable file, a missing key or a value of the wrong JSON type. The
/// caller then uses its built-in default.
public enum ShipFlags {
    /// Top-level JSON object; values are kept as decoded and type-checked on read.
    struct FlagBox: @unchecked Sendable {
        let values: [String: Any]
        init(_ values: [String: Any]) { self.values = values }
    }

    static func parse(_ data: Data) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private static func flags(_ content: ShipContent) -> [String: Any] {
        content.flagsCache.value(for: content) {
            FlagBox(content.url(for: "config/flags.json").flatMap { try? Data(contentsOf: $0) }.map(parse) ?? [:])
        }.values
    }

    // MARK: Typed reads on parsed flags

    static func bool(_ key: String, in flags: [String: Any]) -> Bool? {
        // JSONSerialization turns true/false into NSNumber; reject plain numbers posing as bools.
        guard let n = flags[key] as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
        return n.boolValue
    }

    static func int(_ key: String, in flags: [String: Any]) -> Int? {
        guard let n = flags[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        return d == d.rounded() && abs(d) < Double(Int.max) ? Int(d) : nil
    }

    static func double(_ key: String, in flags: [String: Any]) -> Double? {
        guard let n = flags[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n.doubleValue
    }

    static func string(_ key: String, in flags: [String: Any]) -> String? {
        flags[key] as? String
    }

    // MARK: Public reads on applied content

    public static func bool(_ key: String, in content: ShipContent) -> Bool? { bool(key, in: flags(content)) }
    public static func int(_ key: String, in content: ShipContent) -> Int? { int(key, in: flags(content)) }
    public static func double(_ key: String, in content: ShipContent) -> Double? { double(key, in: flags(content)) }
    public static func string(_ key: String, in content: ShipContent) -> String? { string(key, in: flags(content)) }
}
