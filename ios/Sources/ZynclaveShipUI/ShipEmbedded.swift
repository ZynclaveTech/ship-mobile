import Foundation
import os

/// Server-driven popups that ship INSIDE the app: they show without a Ship
/// release (offline, or for users on a version older than any release), and
/// after a revert. Ship's popups win a shared id. Only popups are embedded.
///
/// Layout in the app bundle (a folder reference, so it is copied as-is):
///
///     ShipEmbedded/sdui/popups/<id>.json
///     ShipEmbedded/lottie/<name>.json     <- assets, at their release paths
///     ShipEmbedded/images/<name>.png
struct ShipEmbedded {
    static let folder = "ShipEmbedded"
    private static let log = Logger(subsystem: "com.zynclave.ship", category: "sdui")

    let root: URL?
    let popups: [SDUI.Popup]

    init(bundle: Bundle, scheme: String, customTypes: Set<String>) {
        let root = bundle.resourceURL?.appending(path: Self.folder, directoryHint: .isDirectory)
        self.root = root
        guard let root else { popups = []; return }
        let popupDir = root.appending(path: "sdui/popups", directoryHint: .isDirectory)
        let names = ((try? FileManager.default.contentsOfDirectory(atPath: popupDir.path)) ?? [])
            .filter { $0.hasSuffix(".json") }.sorted()
        func exists(_ asset: String) -> Bool {
            var isDir: ObjCBool = false
            return FileManager.default.fileExists(atPath: root.appending(path: asset).path, isDirectory: &isDir) && !isDir.boolValue
        }
        var out: [SDUI.Popup] = []
        for name in names {
            guard let data = try? Data(contentsOf: popupDir.appending(path: name)) else { continue }
            do {
                let p = try SDUI.parsePopup(data, scheme: scheme, customTypes: customTypes, assetExists: exists)
                guard !out.contains(where: { $0.id == p.id }) else { continue }
                out.append(p)
            } catch let e as SDUI.Invalid {
                Self.log.error("Embedded popup \(name, privacy: .public) skipped: \(e.reason, privacy: .public)")
            } catch {}
        }
        popups = out
    }

    /// The bundle file for a release path, if the app carries one.
    func url(for asset: String) -> URL? {
        guard let root, !asset.contains("..") else { return nil }
        let url = root.appending(path: asset)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
}
