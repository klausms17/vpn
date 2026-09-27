import NetworkExtension
import SwiftUI

struct HomeView: View {
    @Environment(VpnModel.self) private var vpn

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            Button {
                Task { await vpn.toggle() }
            } label: {
                ZStack {
                    Circle()
                        .fill(vpn.isOn ? Color.green.opacity(0.25) : Color.white.opacity(0.08))
                        .frame(width: 180, height: 180)
                    Image(systemName: "power")
                        .font(.system(size: 56, weight: .medium))
                        .foregroundStyle(vpn.isOn ? Color.green : Color.white)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(vpn.isOn ? "Отключить VPN" : "Подключить VPN")

            VStack(spacing: 6) {
                Text(statusText).font(.headline).foregroundStyle(statusColor)
                Text(vpn.serverName ?? "Добавьте сервер, чтобы подключиться")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()

            PasteButton(payloadType: String.self) { strings in
                if let text = strings.first { vpn.importText(text) }
            }
            .labelStyle(.titleAndIcon)
            .tint(.green)

            if let message = vpn.message {
                Text(message).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            Text("Ядро Xray \(Core.version)").font(.caption2).foregroundStyle(.tertiary)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }

    private var statusText: String {
        switch vpn.status {
        case .connected: "Подключено"
        case .connecting, .reasserting: "Подключение…"
        case .disconnecting: "Отключение…"
        default: "Отключено"
        }
    }

    private var statusColor: Color {
        switch vpn.status {
        case .connected: .green
        case .connecting, .reasserting, .disconnecting: .orange
        default: .secondary
        }
    }
}
