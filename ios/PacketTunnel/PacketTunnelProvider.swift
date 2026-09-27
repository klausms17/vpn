import Foundation
import Libxray
import NetworkExtension

/// The VPN itself: runs the Xray core on the tunnel interface iOS creates
/// for this extension. Everything it needs is read from the App Group, so
/// it also starts without the app (e.g. "Постоянный VPN").
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var controller: LibxrayController?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        // The whole extension must stay under about 50 MB.
        LibxraySetMemoryLimit(32 << 20, 50)
        if let geo = Bundle.main.resourceURL?.appendingPathComponent("geo").path {
            LibxrayInitEnv(geo)
        }
        var crashError: NSError?
        _ = LibxraySetCrashLog(AppGroup.logsDir.appendingPathComponent("crash.log").path, &crashError)

        let config: String
        let settings: TunnelSettings
        do {
            let profile = try String(contentsOf: AppGroup.profileFile, encoding: .utf8)
            config = try Core.tunnelConfig(
                profileJSON: profile,
                logFile: AppGroup.logsDir.appendingPathComponent("xray.log").path
            )
            settings = try TunnelSettings.decode(LibxrayTunSettingsApple(true))
        } catch {
            completionHandler(error)
            return
        }

        setTunnelNetworkSettings(settings.network) { [weak self] error in
            guard let self else { return }
            if let error {
                completionHandler(error)
                return
            }
            // The interface exists only now.
            let fd = LibxrayTunnelFD()
            guard fd >= 0 else {
                completionHandler(CoreError(message: "Не удалось получить туннель от системы"))
                return
            }
            do {
                guard let controller = LibxrayNewController() else {
                    throw CoreError(message: "Ядро не создано")
                }
                try controller.start(config, tunFd: fd)
                self.controller = controller
                completionHandler(nil)
            } catch {
                completionHandler(CoreError(message: "Ядро не запустилось: \(error.localizedDescription)"))
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        try? controller?.stop()
        controller = nil
        completionHandler()
    }
}
