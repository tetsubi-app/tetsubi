// MARK: - ServerDiscovery — find Jellyfin servers on the local network

import Foundation
#if canImport(Darwin)
import Darwin
#endif

/// A Jellyfin server that answered on the local network.
public struct DiscoveredServer: Identifiable, Hashable, Sendable {
    /// The server's ID (stable across restarts and address changes).
    public let id: String
    /// The name set in the server's dashboard, e.g. "Jellyfin-Box".
    public let name: String
    /// The address to sign in to, e.g. "http://192.168.1.20:8096".
    public let address: String
}

/// Finds Jellyfin servers on the local network. Jellyfin's discovery: send
/// "Who is JellyfinServer?" to UDP port 7359; each server replies with JSON
/// `{"Address": …, "Id": …, "Name": …}`. Servers only answer when "Enable
/// auto discovery" is on (Dashboard ▸ Networking, on by default).
///
/// The message goes out two ways at once:
/// - **Broadcast** to the whole network, as Jellyfin's own apps do.
/// - **Direct** to every address on the Wi-Fi/Ethernet network (a sweep:
///   ~250 small packets on a typical home /24, at most ~1,000). Needed
///   because Docker's default bridge networking forwards a published
///   `7359/udp` only for packets addressed to the host, never broadcasts.
///   It also works on iOS without Apple's multicast entitlement, which only
///   broadcast/multicast needs; without it the broadcast send just fails.
///
/// Only Wi-Fi/Ethernet (`en*`) private networks are swept, never mobile data.
/// The sandboxed Mac app needs `com.apple.security.network.server` to receive
/// the replies.
public enum ServerDiscovery {

    static let port: UInt16 = 7359
    static let message = "Who is JellyfinServer?"

    private struct Reply: Decodable {
        let Address: String?
        let Id: String?
        let Name: String?
    }

    /// Parses one server's reply. `senderIP` is the address the reply came from.
    static func parse(_ data: Data, senderIP: String) -> DiscoveredServer? {
        guard let reply = try? JSONDecoder().decode(Reply.self, from: data),
              let id = reply.Id, !id.isEmpty,
              let reported = reply.Address, !reported.isEmpty
        else { return nil }
        let name = reply.Name.flatMap { $0.isEmpty ? nil : $0 } ?? "Jellyfin server"
        return DiscoveredServer(id: id, name: name,
                                address: preferredAddress(reported: reported, senderIP: senderIP))
    }

    /// The address to offer. A server in Docker reports its container
    /// address (e.g. http://172.19.0.2:8096), which nothing else can reach;
    /// when the reported host is a bare IP that isn't where the reply came
    /// from, use the sender's IP with the reported scheme and port. A
    /// hostname (e.g. https://music.example.com) is kept: it may be what the
    /// server's certificate is for.
    static func preferredAddress(reported: String, senderIP: String) -> String {
        guard var parts = URLComponents(string: reported),
              let host = parts.host,
              isIPAddress(host), host != senderIP, !senderIP.isEmpty
        else { return reported }
        parts.host = senderIP
        return parts.string ?? reported
    }

    private static func isIPAddress(_ host: String) -> Bool {
        // `URLComponents.host` keeps an IPv6 address's square brackets ("[::1]"); inet_pton wants none.
        let bare = host.hasPrefix("[") && host.hasSuffix("]") ? String(host.dropFirst().dropLast()) : host
        var v4 = in_addr(), v6 = in6_addr()
        return inet_pton(AF_INET, bare, &v4) == 1 || inet_pton(AF_INET6, bare, &v6) == 1
    }

    /// Keeps one entry per server (the first reply wins), sorted by name.
    static func merge(_ servers: [DiscoveredServer]) -> [DiscoveredServer] {
        var seen = Set<String>()
        return servers
            .filter { seen.insert($0.id).inserted }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// Broadcasts once and collects replies for `timeout` seconds. Returns an
    /// empty list if nothing answers or the network won't allow broadcasts.
    public static func search(timeout: TimeInterval = 2.5) async -> [DiscoveredServer] {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: merge(blockingSearch(timeout: timeout)))
            }
        }
    }

    // MARK: - Which addresses to ask

    /// The largest network swept in full; bigger ones are swept only across
    /// the /22 around this device's own address.
    static let largestSweptPrefix = 22

    /// Every host address on the network `ip`/`mask` (host byte order),
    /// except this device, the network address and the broadcast address.
    /// Networks larger than /22 are cut to the /22 around `ip`.
    static func sweepTargets(ip: UInt32, mask: UInt32) -> [UInt32] {
        var mask = mask
        let limit = UInt32.max << UInt32(32 - largestSweptPrefix)
        if mask.nonzeroBitCount < largestSweptPrefix { mask = limit }
        let network = ip & mask
        let broadcast = network | ~mask
        guard broadcast > network + 1 else { return [] }   // /31, /32: no hosts
        return (network + 1 ..< broadcast).filter { $0 != ip }
    }

    /// Whether `ip` (host byte order) is in a private home/office range:
    /// 10/8, 172.16/12 or 192.168/16. Public and link-local ranges aren't swept.
    static func isPrivate(_ ip: UInt32) -> Bool {
        ip >> 24 == 10 || ip >> 20 == (172 << 4 | 1) || ip >> 16 == (192 << 8 | 168)
    }

    private struct LocalNetwork { let ip: UInt32; let mask: UInt32; let broadcast: UInt32 }

    /// Active IPv4 Wi-Fi/Ethernet networks (`en*`) on private addresses.
    private static func localNetworks() -> [LocalNetwork] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var result: [LocalNetwork] = []
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let e = entry.pointee
            let flags = Int32(e.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0,
                  flags & IFF_BROADCAST != 0, flags & IFF_LOOPBACK == 0,
                  String(cString: e.ifa_name).hasPrefix("en"),
                  let addr = e.ifa_addr, addr.pointee.sa_family == sa_family_t(AF_INET),
                  let netmask = e.ifa_netmask
            else { continue }
            let ip = hostOrder(addr), mask = hostOrder(netmask)
            guard isPrivate(ip), mask != 0 else { continue }
            let broadcast = e.ifa_dstaddr.map(hostOrder) ?? (ip | ~mask)
            result.append(LocalNetwork(ip: ip, mask: mask, broadcast: broadcast))
        }
        return result
    }

    private static func hostOrder(_ address: UnsafeMutablePointer<sockaddr>) -> UInt32 {
        address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
            UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
        }
    }

    // MARK: - Sockets

    private static func blockingSearch(timeout: TimeInterval) -> [DiscoveredServer] {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return [] }
        defer { close(fd) }

        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))
        // Short receive timeout, so the loop can check the deadline.
        var wait = timeval(tv_sec: 0, tv_usec: 250_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))

        let networks = localNetworks()
        var sentAny = false

        // Broadcast. macOS often refuses 255.255.255.255 ("no route to
        // host") when several interfaces are up, so each network's own
        // broadcast address too. On iOS without the multicast entitlement
        // these fail, which is fine: the sweep below still runs.
        var broadcasts = Set(networks.map(\.broadcast))
        broadcasts.insert(UInt32.max)
        for target in broadcasts where send(message, to: target, on: fd) { sentAny = true }

        // Sweep: the same message to each address. A short pause every 64
        // sends keeps the burst from overrunning the network buffers.
        var targets = Set<UInt32>()
        for network in networks { targets.formUnion(sweepTargets(ip: network.ip, mask: network.mask)) }
        for (index, target) in targets.sorted().enumerated() {
            if send(message, to: target, on: fd) { sentAny = true }
            if index % 64 == 63 { usleep(2_000) }
        }
        guard sentAny else { return [] }

        var found: [DiscoveredServer] = []
        var buffer = [UInt8](repeating: 0, count: 8192)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            var from = sockaddr_in()
            var fromLength = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(fd, &buffer, buffer.count, 0, $0, &fromLength)
                }
            }
            if count < 0 {
                // Timed out: check the deadline. Other errors are often
                // left over from the sweep (an address with no device
                // reports "host down"); stop only if the socket is unusable.
                if errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR { continue }
                if errno == EHOSTDOWN || errno == EHOSTUNREACH || errno == ECONNREFUSED { continue }
                break
            }
            guard count > 0 else { continue }
            let data = Data(buffer[0..<count])
            if let server = parse(data, senderIP: ipString(from.sin_addr)) {
                found.append(server)
            }
        }
        return found
    }

    /// Sends `text` to `ip` (host byte order) on the discovery port.
    private static func send(_ text: String, to ip: UInt32, on fd: Int32) -> Bool {
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = ip.bigEndian
        let bytes = Array(text.utf8)
        let sent = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                sendto(fd, bytes, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return sent == bytes.count
    }

    private static func ipString(_ address: in_addr) -> String {
        var address = address
        var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address, &text, socklen_t(INET_ADDRSTRLEN)) != nil else { return "" }
        return String(cString: text)
    }
}
