import SwiftUI

@main
struct ShipSampleApp: App {
    @StateObject private var ship = Ship()

    var body: some Scene {
        WindowGroup { ContentView(ship: ship) }
    }
}
