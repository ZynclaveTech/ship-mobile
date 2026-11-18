import Foundation
import SwiftUI
import ZynclaveShip
import ZynclaveShipUI

/// The release's public key. Replace it with the key your Ship app gives you.
enum SampleKey {
    static let publicKey = "2iX4H1QZbH2lPtz8hvFK1bCXWHXAUV8N0mGvR+Hn52c="
    static var isPlaceholder: Bool { publicKey == "REPLACE_WITH_SHIP_SAMPLE_PUBLIC_KEY" }
}

/// Turns `shipsample://details` into navigation. Used by `.onOpenURL` and by
/// Ship's `openDeeplink`, so a card button and a link from outside behave alike.
@MainActor
final class Router: ObservableObject {
    @Published var showDetails = false

    func open(_ url: URL) {
        if url.scheme == "shipsample", url.host == "details" { showDetails = true }
    }
}

/// Owns the Ship client and the UI helpers, and syncs on request.
@MainActor
final class Ship: ObservableObject {
    let client: ShipClient?
    let cards: ShipCards?
    let popups: ShipPopups?

    /// Changes after each sync, so views re-read the applied content.
    @Published private(set) var releaseID: String?
    @Published private(set) var seq: Int64?
    @Published private(set) var status = ""

    let channel: String
    let runtime: String
    var isConfigured: Bool { client != nil }

    let router: Router
    private let options: ShipUIOptions?

    /// True while a sync runs, so "Check now" can be disabled.
    @Published private(set) var syncing = false

    /// The language Ship uses for text, so the greeting matches popups.
    var language: String {
        options?.language() ?? Locale.current.language.languageCode?.identifier ?? "en"
    }

    init() {
        let router = Router()
        self.router = router
        #if DEBUG
        channel = "testflight"
        #else
        channel = "production"
        #endif
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        runtime = "ios-" + version

        guard !SampleKey.isPlaceholder else {
            client = nil; cards = nil; popups = nil; options = nil
            status = "Ship is not configured: see README"
            return
        }
        guard let key = PinnedKey(kid: "ship-sample-1", base64: SampleKey.publicKey) else {
            client = nil; cards = nil; popups = nil; options = nil
            status = "Ship public key is invalid: see README"
            return
        }
        let config = ShipConfig(
            cdn: URL(string: "https://ota.zynclave.com/")!,
            app: "ship-sample",
            channel: channel,
            runtime: runtime,
            pinned: [key],
            bucket: ShipConfig.stableBucket()
        )
        let root = URL.applicationSupportDirectory.appending(path: "ship")
        let client = ShipClient(config: config, root: root)
        let options = ShipUIOptions(
            deeplinkScheme: "shipsample",
            openDeeplink: { router.open($0) },
            // Popups bundled in the app show even before the first sync.
            embeddedPopups: .main
        )
        self.client = client
        self.options = options
        cards = ShipCards(client: client, options: options)
        popups = ShipPopups(client: client, options: options)
        publish()
    }

    /// Fetches the latest release, then lets cards and popups re-check. A second call
    /// while one is running does nothing; a cancelled sync leaves no trace.
    func sync() async {
        guard let client, !syncing else { return }
        syncing = true
        defer { syncing = false }
        do {
            switch try await client.sync() {
            case .applied: status = "Updated"
            case .upToDate: status = "Up to date"
            case .noUpdate: status = "No update for this device"
            }
        } catch is CancellationError {
            return
        } catch let error as URLError where error.code == .cancelled {
            return
        } catch {
            status = "Sync failed: \(error.localizedDescription)"
        }
        publish()
        cards?.refresh()
        popups?.armSoon()
    }

    /// Debug screen only: forget which popups were shown, then look again.
    func resetPopups() {
        guard let popups else { return }
        popups.resetShownForDebug()
        popups.armSoon()
        status = "Popup memory reset"
    }

    var content: ShipContent? { client?.content }

    private func publish() {
        let state = client?.content.state
        releaseID = state?.releaseID
        seq = state?.seq
    }
}
