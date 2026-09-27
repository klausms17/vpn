import Foundation
import Libxray

/// An error the core returned, with its (Russian or technical) text.
struct CoreError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// The Go/Xray core, shared with the Android app (libxray).
enum Core {
    static var version: String { LibxrayVersion() }

    /// Parses a share link (vless://, vmess://, trojan://, ss://, hy2://)
    /// and returns the profile as the core describes it (JSON).
    static func parseLink(_ link: String) throws -> String {
        var error: NSError?
        let json = LibxrayParseLink(link, &error)
        if let error { throw CoreError(message: error.localizedDescription) }
        return json
    }

    /// The full Xray config for a profile: Russian sites directly, the rest
    /// through the server, traffic read from the tunnel interface.
    static func tunnelConfig(profileJSON: String, logFile: String) throws -> String {
        guard let data = profileJSON.data(using: .utf8),
              let profile = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let outbounds = profile["outbounds"]
        else { throw CoreError(message: "Сервер сохранён с ошибкой: добавьте ключ заново") }
        let options: [String: Any] = [
            "outbounds": outbounds,
            "mode": "ru_direct",
            "ipv6": true,
            "tun": true,
            "tunMtu": 1500,
            "logLevel": "warning",
            "logFile": logFile,
        ]
        let optionsData = try JSONSerialization.data(withJSONObject: options)
        var error: NSError?
        let config = LibxrayBuildConfig(String(decoding: optionsData, as: UTF8.self), &error)
        if let error { throw CoreError(message: error.localizedDescription) }
        return config
    }
}
