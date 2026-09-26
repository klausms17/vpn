import Foundation

/// Files the app and the tunnel extension share (the App Group container).
enum AppGroup {
    static let id = "group.com.klausms.vpn"
    static let tunnelBundleId = "com.klausms.vpn.tunnel"

    static var container: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)
            ?? FileManager.default.temporaryDirectory
    }

    /// The server to connect to: a profile as the core parsed it (JSON).
    static var profileFile: URL { container.appendingPathComponent("profile.json") }

    static var logsDir: URL {
        let dir = container.appendingPathComponent("logs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}
