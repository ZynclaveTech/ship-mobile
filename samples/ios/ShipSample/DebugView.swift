import SwiftUI

struct DebugView: View {
    @ObservedObject var ship: Ship

    var body: some View {
        NavigationStack {
            List {
                row("Release", ship.releaseID ?? "none")
                row("Seq", ship.seq.map(String.init) ?? "none")
                row("Channel", ship.channel)
                row("Runtime", ship.runtime)
                if !ship.status.isEmpty { row("Status", ship.status) }
                Button("Check now") { Task { await ship.sync() } }
                    .disabled(!ship.isConfigured)
            }
            .navigationTitle("Debug")
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        LabeledContent(title, value: value)
    }
}
