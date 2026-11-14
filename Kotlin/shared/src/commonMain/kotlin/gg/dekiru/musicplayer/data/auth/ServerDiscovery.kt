package gg.dekiru.musicplayer.data.auth

import gg.dekiru.musicplayer.data.api.TetsubiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Jellyfin server that answered on the local network. */
data class DiscoveredServer(
    /** The server's ID (stable across restarts and address changes). */
    val id: String,
    /** The name set in the server's dashboard, e.g. "Jellyfin-Box". */
    val name: String,
    /** The address to sign in to, e.g. "http://192.168.1.20:8096". */
    val address: String,
)

/**
 * Finds Jellyfin servers on the local network, as on iOS: send "Who is
 * JellyfinServer?" to UDP port 7359; each server replies with JSON
 * `{"Address": …, "Id": …, "Name": …}` (when "Enable auto discovery" is on,
 * the default).
 *
 * The message goes out two ways at once: broadcast to the whole network, and
 * directly to every address on the Wi-Fi/Ethernet network (a sweep: ~250
 * packets on a home /24, at most ~1,000), because Docker's default bridge
 * networking forwards a published `7359/udp` only for packets addressed to
 * the host, never broadcasts. Only private Wi-Fi/Ethernet networks are swept,
 * never mobile data. The sockets are in jvmSharedMain.
 */
object ServerDiscovery {

    const val PORT = 7359
    const val MESSAGE = "Who is JellyfinServer?"

    /** The largest network swept in full; bigger ones are swept only across the /22 around this device. */
    const val LARGEST_SWEPT_PREFIX = 22

    /** Broadcasts and sweeps once, collects replies for `timeoutMillis`. Empty if nothing answers. */
    suspend fun search(timeoutMillis: Long = 2_500): List<DiscoveredServer> = merge(udpSearch(timeoutMillis))

    @Serializable
    private data class Reply(
        @SerialName("Address") val address: String? = null,
        @SerialName("Id") val id: String? = null,
        @SerialName("Name") val name: String? = null,
    )

    /** Parses one server's reply. `senderIp` is the address the reply came from. */
    fun parse(text: String, senderIp: String): DiscoveredServer? {
        val reply = try {
            TetsubiJson.decodeFromString(Reply.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        val id = reply.id?.takeIf { it.isNotEmpty() } ?: return null
        val reported = reply.address?.takeIf { it.isNotEmpty() } ?: return null
        val name = reply.name?.takeIf { it.isNotEmpty() } ?: "Jellyfin server"
        return DiscoveredServer(id, name, preferredAddress(reported, senderIp))
    }

    /**
     * The address to offer. A server in Docker reports its container address
     * (e.g. http://172.19.0.2:8096), which nothing else can reach; when the
     * reported host is a bare IP that isn't where the reply came from, use the
     * sender's IP with the reported scheme and port. A host name is kept: it
     * may be what the server's certificate is for.
     */
    fun preferredAddress(reported: String, senderIp: String): String {
        val parts = UrlParts.parse(reported) ?: return reported
        if (senderIp.isEmpty() || parts.host == senderIp || !UrlParts.isIpAddress(parts.host)) return reported
        return parts.copy(host = senderIp).toString()
    }

    /** One entry per server (the first reply wins), sorted by name. */
    fun merge(servers: List<DiscoveredServer>): List<DiscoveredServer> =
        servers.distinctBy { it.id }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * Every host address on the network `ip`/`mask`, except this device, the
     * network address and the broadcast address. Networks larger than /22
     * are cut to the /22 around `ip`.
     */
    fun sweepTargets(ip: UInt, mask: UInt): List<UInt> {
        var netmask = mask
        if (netmask.countOneBits() < LARGEST_SWEPT_PREFIX) netmask = UInt.MAX_VALUE shl (32 - LARGEST_SWEPT_PREFIX)
        val network = ip and netmask
        val broadcast = network or netmask.inv()
        if (broadcast <= network + 1u) return emptyList() // /31, /32: no hosts
        return (network + 1u until broadcast).filter { it != ip }
    }

    /** 10/8, 172.16/12 or 192.168/16. Public and link-local ranges aren't swept. */
    fun isPrivate(ip: UInt): Boolean =
        ip shr 24 == 10u || ip shr 20 == (172u shl 4 or 1u) || ip shr 16 == (192u shl 8 or 168u)

    /** "192.168.1.20" → its 32-bit value. */
    fun ipv4(text: String): UInt = text.split('.').fold(0u) { acc, part -> acc shl 8 or part.toUInt() }

    fun ipv4String(ip: UInt): String = (3 downTo 0).joinToString(".") { ((ip shr (it * 8)) and 0xFFu).toString() }
}

/** Sends the broadcast and the sweep, collects raw replies until `timeoutMillis`. */
internal expect suspend fun udpSearch(timeoutMillis: Long): List<DiscoveredServer>
