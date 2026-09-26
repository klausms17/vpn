import Foundation
import NetworkExtension
import Observation

/// The app's view of the VPN: the saved server and the system tunnel.
@MainActor
@Observable
final class VpnModel {
    private(set) var status: NEVPNStatus = .invalid
    private(set) var serverName: String?
    var message: String?

    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?

    var isOn: Bool { status == .connected || status == .connecting || status == .reasserting }

    func load() async {
        serverName = Self.savedServerName()
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = managers.first
        } catch {
            message = error.localizedDescription
        }
        watch()
    }

    /// A key (share link) pasted or opened from a link.
    func importText(_ text: String) {
        var link = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if link.lowercased().hasPrefix("klausvpn://add/") {
            link = String(link.dropFirst("klausvpn://add/".count)).removingPercentEncoding ?? link
        }
        do {
            let profile = try Core.parseLink(link)
            try profile.write(to: AppGroup.profileFile, atomically: true, encoding: .utf8)
            serverName = Self.savedServerName()
            message = "Сервер добавлен"
        } catch {
            message = error.localizedDescription
        }
    }

    func toggle() async {
        if isOn {
            manager?.connection.stopVPNTunnel()
            return
        }
        guard serverName != nil else {
            message = "Сначала добавьте ключ"
            return
        }
        do {
            let m = manager ?? Self.newManager()
            m.isEnabled = true
            try await m.saveToPreferences()
            // A freshly saved configuration must be loaded before it starts.
            try await m.loadFromPreferences()
            manager = m
            watch()
            try m.connection.startVPNTunnel()
        } catch {
            message = error.localizedDescription
        }
    }

    private func watch() {
        guard let connection = manager?.connection else { return }
        status = connection.status
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: connection, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.status = connection.status }
        }
    }

    private static func newManager() -> NETunnelProviderManager {
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = AppGroup.tunnelBundleId
        proto.serverAddress = "Klaus VPN"
        let m = NETunnelProviderManager()
        m.protocolConfiguration = proto
        m.localizedDescription = "Klaus VPN"
        return m
    }

    private static func savedServerName() -> String? {
        guard let data = try? Data(contentsOf: AppGroup.profileFile),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        let name = (json["name"] as? String) ?? ""
        return name.isEmpty ? (json["address"] as? String) : name
    }
}
