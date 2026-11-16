import SwiftUI
import ZynclaveShip
import ZynclaveShipUI

struct ContentView: View {
    @ObservedObject var ship: Ship
    @ObservedObject var router: Router
    @Environment(\.scenePhase) private var scenePhase
    @State private var showDebug = false

    init(ship: Ship) {
        self.ship = ship
        router = ship.router
    }

    var body: some View {
        NavigationStack {
            List {
                header
                Text("Plain row 1")
                // Ship's cards sit among your own rows, wherever you choose.
                if let cards = ship.cards {
                    ForEach(cards.cards(tab: "discover"), id: \.id) { card in
                        ShipCardView(card: card, cards: cards)
                    }
                }
                Text("Plain row 2")
            }
            .navigationTitle("Ship Sample")
            .toolbar { Button("Debug") { showDebug = true } }
            .navigationDestination(isPresented: $router.showDetails) { DetailsView() }
            .sheet(isPresented: $showDebug) { DebugView(ship: ship) }
        }
        .modifier(PopupsIfConfigured(popups: ship.popups))
        .onOpenURL { router.open($0) }
        // Runs at launch, and again each time the app becomes active.
        .task(id: scenePhase) { if scenePhase == .active { await ship.sync() } }
    }

    @ViewBuilder private var header: some View {
        Section {
            Text(ship.content?.string("sample_greeting", locale: ship.language) ?? "Hello")
                .font(.title2)
            if let content = ship.content, ShipFlags.bool("show_banner", in: content) == true {
                Label("Banner is on", systemImage: "flag.fill").foregroundStyle(.tint)
            }
            if !ship.isConfigured { Text(ship.status).foregroundStyle(.secondary) }
        }
    }
}

/// `.shipPopups` needs a `ShipPopups`; without a key there is none.
private struct PopupsIfConfigured: ViewModifier {
    let popups: ShipPopups?

    func body(content: Content) -> some View {
        if let popups { content.shipPopups(popups) } else { content }
    }
}
