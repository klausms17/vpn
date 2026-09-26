import SwiftUI

@main
struct KlausVPNApp: App {
    @State private var vpn = VpnModel()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environment(vpn)
                .preferredColorScheme(.dark)
                .task { await vpn.load() }
                // "Добавить в Klaus VPN" links from a subscription page.
                .onOpenURL { url in vpn.importText(url.absoluteString) }
        }
    }
}
