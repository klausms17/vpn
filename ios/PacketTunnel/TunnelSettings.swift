import Foundation
import NetworkExtension

/// The tunnel's addresses, routes and DNS, as the core describes them
/// (LibxrayTunSettingsApple): the same as on Android.
struct TunnelSettings: Decodable {
    struct Route: Decodable {
        let address: String
        let mask: String?
        let prefix: Int?
    }

    let mtu: Int
    let ipv4: Route
    let ipv4Excluded: [Route]?
    let ipv6: String
    let ipv6Prefix: Int
    let ipv6Included: [Route]?
    let dnsServers: [String]

    static func decode(_ json: String) throws -> TunnelSettings {
        try JSONDecoder().decode(TunnelSettings.self, from: Data(json.utf8))
    }

    var network: NEPacketTunnelNetworkSettings {
        let s = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        s.mtu = NSNumber(value: mtu)

        let v4 = NEIPv4Settings(addresses: [ipv4.address], subnetMasks: [ipv4.mask ?? "255.255.255.252"])
        v4.includedRoutes = [NEIPv4Route.default()]
        // Local networks, loopback and the like stay outside the tunnel.
        v4.excludedRoutes = (ipv4Excluded ?? []).map {
            NEIPv4Route(destinationAddress: $0.address, subnetMask: $0.mask ?? "255.255.255.255")
        }
        s.ipv4Settings = v4

        // IPv6 always goes in, so it cannot leak around the VPN.
        let v6 = NEIPv6Settings(addresses: [ipv6], networkPrefixLengths: [NSNumber(value: ipv6Prefix)])
        v6.includedRoutes = (ipv6Included ?? []).map {
            NEIPv6Route(destinationAddress: $0.address, networkPrefixLength: NSNumber(value: $0.prefix ?? 128))
        }
        s.ipv6Settings = v6

        let dns = NEDNSSettings(servers: dnsServers)
        dns.matchDomains = [""] // every name is resolved through the tunnel
        s.dnsSettings = dns
        return s
    }
}
