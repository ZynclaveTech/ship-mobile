import Foundation
import ZynclaveShip

struct NoTransport: ShipTransport {
    func get(_ url: URL, limit: Int64) async throws -> (Data, Int) { throw ShipError.http(503) }
}

/// A scratch directory removed by the test that made it.
func scratch() -> URL {
    let dir = FileManager.default.temporaryDirectory.appending(path: "ship-ui-test-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir
}

func write(_ data: Data, to url: URL) {
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! data.write(to: url)
}

func popupJSON(id: String, priority: Int = 0, audience: String = "all", extra: [String: Any] = [:]) -> Data {
    var obj: [String: Any] = [
        "schema": 1, "id": id, "audience": audience, "priority": priority,
        "body": ["type": "vstack", "children": [["type": "text", "fallback": "Hello \(id)"]]],
    ]
    for (k, v) in extra { obj[k] = v }
    return try! JSONSerialization.data(withJSONObject: obj)
}

func cardJSON(id: String, priority: Int = 0) -> Data {
    let obj: [String: Any] = [
        "schema": 1, "id": id, "audience": "all",
        "placement": ["tabs": ["discover"], "position": 1, "priority": priority],
        "body": ["type": "text", "fallback": "Card \(id)"],
    ]
    return try! JSONSerialization.data(withJSONObject: obj)
}

/// A client whose applied release is already on disk, as after a sync.
/// `files` maps release path -> content.
func client(root: URL, files: [String: Data], revert: Bool = false, releaseID: String = "rel-1") -> ShipClient {
    let cur = root.appending(path: "current")
    for (name, data) in files { write(data, to: cur.appending(path: name)) }
    let state: [String: Any] = [
        "app": "sample", "channel": "prod", "runtime": "rt-1", "releaseID": releaseID, "seq": 1,
        "revertToEmbedded": revert, "assets": files.keys.sorted(),
    ]
    write(try! JSONSerialization.data(withJSONObject: state), to: cur.appending(path: ".ship-state.json"))
    return ShipClient(config: ShipConfig(cdn: URL(string: "https://cdn.test")!, app: "sample", channel: "prod",
                                         runtime: "rt-1", pinned: [], bucket: 0),
                      root: root, transport: NoTransport())
}

/// An app bundle folder with `ShipEmbedded/` files; `files` maps release path -> content.
func embeddedBundle(files: [String: Data]) -> Bundle {
    let dir = scratch().appending(path: "App.bundle")
    for (name, data) in files { write(data, to: dir.appending(path: "ShipEmbedded/\(name)")) }
    return Bundle(url: dir)!
}

func freshDefaults() -> UserDefaults {
    let name = "ship-ui-test-\(UUID().uuidString)"
    let d = UserDefaults(suiteName: name)!
    d.removePersistentDomain(forName: name)
    return d
}
