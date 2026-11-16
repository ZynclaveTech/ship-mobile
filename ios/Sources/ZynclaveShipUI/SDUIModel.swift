//
//  Server-driven cards and popups for Zynclave Ship. A release carries
//  `sdui/cards/<id>.json` and `sdui/popups/<id>.json`: a small tree of known
//  components. This file parses them STRICTLY: anything unknown, oversized or
//  malformed drops the whole definition, never part of it. No code is
//  downloaded; the JSON only arranges components the app already has.
//
//  `audience` is "all" or "targeted". A targeted definition shows only when
//  the app's eligibility list names its id; no list hides it.
//

import Foundation

public enum SDUI {
    public static let schemaVersion = 1
    public static let rendererVersion = 1
    public static let maxNodes = 40
    public static let maxDepth = 6
    public static let maxTextLength = 500
    /// At most this many server cards per tab per feed.
    public static let maxCardsPerFeed = 2

    public struct Card: Equatable, Sendable {
        public let id: String
        /// Shown only to users the backend lists for this id.
        public let targeted: Bool
        public let start: Date?
        public let end: Date?
        public let tabs: Set<String>
        public let position: Int
        public let priority: Int
        public let dismissible: Bool
        public let tap: Action?
        public let body: Node
    }

    public struct TextValue: Equatable, Sendable {
        public let key: String?
        public let fallback: String?
    }

    public enum Action: Equatable, Sendable {
        case deeplink(URL)
        case web(URL)
        case dismiss
    }

    public indirect enum Node: Equatable, Sendable {
        case stack(axis: Axis, spacing: CGFloat, padding: CGFloat, background: String?, cornerRadius: CGFloat, children: [Node])
        case text(TextValue, style: String, maxLines: Int?, color: String?)
        case image(asset: String, aspect: CGFloat?, cornerRadius: CGFloat)
        /// A Lottie animation from the same release (`lottie/<name>.json`).
        case lottie(asset: String, height: CGFloat, loop: Bool)
        case button(TextValue, style: String, action: Action)
        case badge(TextValue, color: String?)
        case spacer(height: CGFloat)
        /// An app-registered component (its `type` is in `customTypes`). The
        /// app renders it from `fields`; the library never interprets them.
        case custom(CustomNode)

        public enum Axis: Equatable, Sendable { case vertical, horizontal }
    }

    /// A node whose `type` the app registered. `fields` is every other key.
    public struct CustomNode: Equatable, Sendable {
        public let type: String
        public let fields: [String: SDUIJSON]
    }

    public struct Invalid: Error, Equatable { public let reason: String }
}

// MARK: - Parsing (strict)

extension SDUI {
    static let textStyles: Set<String> = ["title", "headline", "body", "caption"]
    static let buttonStyles: Set<String> = ["primary", "secondary", "text"]
    static let colorTokens: Set<String> = ["primary", "accent", "surface", "surfaceVariant", "onSurface", "onSurfaceVariant", "onPrimary"]

    /// Parses one card file. `assetExists` says whether an `images/...` path is
    /// in the same release; an image that isn't makes the card invalid.
    public static func parseCard(_ data: Data, scheme: String, customTypes: Set<String>,
                                 assetExists: (String) -> Bool) throws -> Card {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw Invalid(reason: "not a JSON object")
        }
        guard let schema = obj["schema"] as? Int, schema >= 1, schema <= schemaVersion else {
            throw Invalid(reason: "unsupported schema")
        }
        if let min = obj["minRenderer"] {
            guard let m = min as? Int, m <= rendererVersion else { throw Invalid(reason: "needs a newer renderer") }
        }
        guard let id = obj["id"] as? String, id.range(of: #"^[a-z0-9-]{1,64}$"#, options: .regularExpression) != nil else {
            throw Invalid(reason: "bad id")
        }
        let targeted = try audienceIsTargeted(obj["audience"])
        let start = try optionalDate(obj["start"], "start")
        let end = try optionalDate(obj["end"], "end")

        guard let placement = obj["placement"] as? [String: Any],
              let tabs = placement["tabs"] as? [String], !tabs.isEmpty,
              tabs.allSatisfy({ $0 == "discover" || $0 == "following" }),
              let position = placement["position"] as? Int, (0...50).contains(position) else {
            throw Invalid(reason: "bad placement")
        }
        let priority = placement["priority"] as? Int ?? 0
        let dismissible = obj["dismissible"] as? Bool ?? true
        let tap = try obj["tap"].map { try action($0, scheme: scheme) }

        var count = 0
        guard let bodyObj = obj["body"] else { throw Invalid(reason: "no body") }
        let body = try node(bodyObj, depth: 1, count: &count, scheme: scheme, customTypes: customTypes, assetExists: assetExists)
        return Card(id: id, targeted: targeted, start: start, end: end, tabs: Set(tabs), position: position,
                    priority: priority, dismissible: dismissible, tap: tap, body: body)
    }

    /// "all" or "targeted"; anything else - a typo, a value from a newer
    /// spec - drops the definition rather than guessing who should see it.
    static func audienceIsTargeted(_ value: Any?) throws -> Bool {
        switch value as? String {
        case "all": return false
        case "targeted": return true
        default: throw Invalid(reason: "bad audience")
        }
    }

    private static func optionalDate(_ value: Any?, _ field: String) throws -> Date? {
        guard let value else { return nil }
        guard let s = value as? String, let d = ISO8601DateFormatter().date(from: s) else {
            throw Invalid(reason: "bad \(field)")
        }
        return d
    }

    static func action(_ value: Any, scheme: String) throws -> Action {
        guard let obj = value as? [String: Any], let type = obj["type"] as? String else { throw Invalid(reason: "bad action") }
        switch type {
        case "dismiss":
            return .dismiss
        case "deeplink":
            guard let s = obj["url"] as? String, let url = URL(string: s), url.scheme == scheme, url.host != nil else {
                throw Invalid(reason: "deeplink must be \(scheme)://")
            }
            return .deeplink(url)
        case "web":
            guard let s = obj["url"] as? String, let url = URL(string: s), url.scheme == "https", url.host != nil else {
                throw Invalid(reason: "web link must be https://")
            }
            return .web(url)
        default:
            throw Invalid(reason: "unknown action \(type)")
        }
    }

    private static func text(_ value: Any?) throws -> TextValue {
        guard let obj = value as? [String: Any] else { throw Invalid(reason: "bad text") }
        let key = obj["key"] as? String
        let fallback = obj["fallback"] as? String
        guard key != nil || fallback != nil else { throw Invalid(reason: "text needs key or fallback") }
        if let f = fallback, f.count > maxTextLength { throw Invalid(reason: "text too long") }
        return TextValue(key: key, fallback: fallback)
    }

    /// `{first_name}` is the only placeholder text may carry; it becomes the
    /// signed-in user's first name, or is dropped (with its leading ", ") if
    /// there is none - "Happy Diwali, {first_name}!" -> "Happy Diwali!".
    public static func personalize(_ text: String, firstName: String?) -> String {
        guard text.contains("{first_name}") else { return text }
        if let n = firstName?.trimmingCharacters(in: .whitespaces), !n.isEmpty {
            return text.replacingOccurrences(of: "{first_name}", with: n)
        }
        // Edit ONLY around the placeholder; the rest of the text (incl. a
        // French space before "!") is left exactly as written.
        //   start:          "{first_name}, welcome"   -> "welcome"
        //                   "{first_name}! Welcome"   -> "Welcome"
        //   after a comma:  "Happy Diwali, {first_name}!" -> "Happy Diwali!"
        //   anywhere else:  drop it with the spaces BEFORE it, keep what
        //                   follows - "Hey {first_name}, claim" -> "Hey, claim",
        //                   "Hi {first_name} there" -> "Hi there"
        return text
            .replacingOccurrences(of: #"^\{first_name\}[,!:;.?]?[ \t]*"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #",[ \t]*\{first_name\}"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"[ \t]*\{first_name\}"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    private static func color(_ value: Any?) throws -> String? {
        guard let value else { return nil }
        guard let obj = value as? [String: Any], let token = obj["token"] as? String, colorTokens.contains(token) else {
            throw Invalid(reason: "colors must be theme tokens")
        }
        return token
    }

    private static func number(_ value: Any?, default d: CGFloat, max: CGFloat) throws -> CGFloat {
        guard let value else { return d }
        guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue >= 0, n.doubleValue <= Double(max) else {
            throw Invalid(reason: "bad number")
        }
        return CGFloat(n.doubleValue)
    }

    static func node(_ value: Any, depth: Int, count: inout Int, scheme: String, customTypes: Set<String>,
                     assetExists: (String) -> Bool) throws -> Node {
        count += 1
        guard count <= maxNodes else { throw Invalid(reason: "too many nodes") }
        guard depth <= maxDepth else { throw Invalid(reason: "nested too deep") }
        guard let obj = value as? [String: Any], let type = obj["type"] as? String else { throw Invalid(reason: "bad node") }
        switch type {
        case "vstack", "hstack":
            guard let raw = obj["children"] as? [Any], !raw.isEmpty else { throw Invalid(reason: "stack needs children") }
            let children = try raw.map { try node($0, depth: depth + 1, count: &count, scheme: scheme, customTypes: customTypes, assetExists: assetExists) }
            return .stack(axis: type == "vstack" ? .vertical : .horizontal,
                          spacing: try number(obj["spacing"], default: 8, max: 64),
                          padding: try number(obj["padding"], default: 0, max: 64),
                          background: try color(obj["background"]),
                          cornerRadius: try number(obj["cornerRadius"], default: 0, max: 48),
                          children: children)
        case "text":
            let style = obj["style"] as? String ?? "body"
            guard textStyles.contains(style) else { throw Invalid(reason: "bad text style") }
            let maxLines = obj["maxLines"] as? Int
            if let m = maxLines, !(1...20).contains(m) { throw Invalid(reason: "bad maxLines") }
            return .text(try text(obj), style: style, maxLines: maxLines, color: try color(obj["color"]))
        case "image":
            guard let src = obj["src"] as? String, src.hasPrefix("images/"), !src.contains(".."), assetExists(src) else {
                throw Invalid(reason: "image must be an asset in this release")
            }
            let aspect = try obj["aspect"].map { _ in try number(obj["aspect"], default: 1, max: 10) }
            if let a = aspect, a < 0.1 { throw Invalid(reason: "bad aspect") }
            return .image(asset: src, aspect: aspect, cornerRadius: try number(obj["cornerRadius"], default: 0, max: 48))
        case "lottie":
            guard let src = obj["src"] as? String, src.hasPrefix("lottie/"), src.hasSuffix(".json"),
                  !src.contains(".."), assetExists(src) else {
                throw Invalid(reason: "lottie must be an asset in this release")
            }
            return .lottie(asset: src, height: try number(obj["height"], default: 160, max: 480),
                           loop: obj["loop"] as? Bool ?? true)
        case "button":
            let style = obj["style"] as? String ?? "primary"
            guard buttonStyles.contains(style), let act = obj["action"] else { throw Invalid(reason: "bad button") }
            return .button(try text(obj["label"]), style: style, action: try action(act, scheme: scheme))
        case "badge":
            return .badge(try text(obj["text"]), color: try color(obj["color"]))
        case "spacer":
            return .spacer(height: try number(obj["height"], default: 8, max: 64))
        default:
            guard customTypes.contains(type) else { throw Invalid(reason: "unknown component \(type)") }
            var fields: [String: SDUIJSON] = [:]
            for (k, v) in obj where k != "type" { fields[k] = SDUIJSON(v) }
            return .custom(CustomNode(type: type, fields: fields))
        }
    }

    /// Which cards a feed tab shows now: valid, in schedule, not dismissed,
    /// targeted ones only if `eligible` names them, highest priority first,
    /// at most `maxCardsPerFeed`.
    public static func cards(_ all: [Card], tab: String, now: Date, dismissed: Set<String>, eligible: Set<String>) -> [Card] {
        // One card per id: ids become diffable item identifiers, and a
        // duplicate identifier in a snapshot is a fatal UIKit error.
        var seen = Set<String>()
        return all.filter { seen.insert($0.id).inserted }.filter { c in
            c.tabs.contains(tab) && !dismissed.contains(c.id) && (!c.targeted || eligible.contains(c.id))
                && (c.start.map { now >= $0 } ?? true) && (c.end.map { now <= $0 } ?? true)
        }
        .sorted { ($0.priority, $1.id) > ($1.priority, $0.id) }
        .prefix(maxCardsPerFeed)
        .map { $0 }
    }
}

extension SDUI {
    public struct Popup: Equatable, Sendable {
        public let id: String
        /// Shown only to users the backend lists for this id.
        public let targeted: Bool
        public let start: Date?
        public let end: Date?
        public let priority: Int
        public let closeButton: Bool
        public let body: Node
    }

    /// Same strictness as `parseCard`: anything unknown or malformed drops
    /// the whole popup.
    public static func parsePopup(_ data: Data, scheme: String, customTypes: Set<String>,
                                  assetExists: (String) -> Bool) throws -> Popup {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw Invalid(reason: "not a JSON object")
        }
        guard let schema = obj["schema"] as? Int, schema >= 1, schema <= schemaVersion else {
            throw Invalid(reason: "unsupported schema")
        }
        if let min = obj["minRenderer"] {
            guard let m = min as? Int, m <= rendererVersion else { throw Invalid(reason: "needs a newer renderer") }
        }
        guard let id = obj["id"] as? String, id.range(of: #"^[a-z0-9-]{1,64}$"#, options: .regularExpression) != nil else {
            throw Invalid(reason: "bad id")
        }
        let targeted = try audienceIsTargeted(obj["audience"])
        let f = ISO8601DateFormatter()
        func date(_ key: String) throws -> Date? {
            guard let v = obj[key] else { return nil }
            guard let s = v as? String, let d = f.date(from: s) else { throw Invalid(reason: "bad \(key)") }
            return d
        }
        guard let bodyObj = obj["body"] else { throw Invalid(reason: "no body") }
        var count = 0
        let body = try node(bodyObj, depth: 1, count: &count, scheme: scheme, customTypes: customTypes, assetExists: assetExists)
        return Popup(id: id, targeted: targeted, start: try date("start"), end: try date("end"),
                     priority: obj["priority"] as? Int ?? 0,
                     closeButton: obj["closeButton"] as? Bool ?? true, body: body)
    }

    /// The popup to show now: in schedule, never shown, highest priority
    /// (ties by id, so every device picks the same one).
    public static func nextPopup(_ all: [Popup], now: Date, shown: Set<String>, eligible: Set<String>) -> Popup? {
        eligiblePopups(all, now: now, shown: shown, eligible: eligible).first
    }

    /// In schedule, never shown, targeted ones only if `eligible` names them,
    /// best first (priority, then id).
    public static func eligiblePopups(_ all: [Popup], now: Date, shown: Set<String>, eligible: Set<String>) -> [Popup] {
        all.filter { p in
            !shown.contains(p.id) && (!p.targeted || eligible.contains(p.id))
                && (p.start.map { now >= $0 } ?? true) && (p.end.map { now <= $0 } ?? true)
        }
        .sorted { ($0.priority, $1.id) > ($1.priority, $0.id) }
    }
}

// MARK: - JSON

/// A small JSON value, for the fields of a custom node.
public enum SDUIJSON: Equatable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case null
    case array([SDUIJSON])
    case object([String: SDUIJSON])

    /// From a `JSONSerialization` value.
    init(_ value: Any) {
        switch value {
        case let s as String: self = .string(s)
        case let n as NSNumber:
            self = CFGetTypeID(n) == CFBooleanGetTypeID() ? .bool(n.boolValue) : .number(n.doubleValue)
        case let a as [Any]: self = .array(a.map(SDUIJSON.init))
        case let o as [String: Any]: self = .object(o.mapValues(SDUIJSON.init))
        default: self = .null
        }
    }
}
