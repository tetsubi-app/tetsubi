package gg.dekiru.musicplayer.data.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** An IPv4 network this device is on. */
private data class LocalNetwork(val ip: UInt, val mask: UInt, val broadcast: UInt)

/**
 * Interfaces never swept: mobile data, VPNs, virtual machines and container
 * bridges. Wi-Fi and Ethernet are named differently per system (wlan0, en0,
 * eth0, enp3s0…), so this lists what to skip rather than what to keep.
 */
private val skippedInterfaces = listOf(
    "rmnet", "ccmni", "pdp", "v4-", "dummy", "tun", "tap", "utun", "wg", "tailscale", "zt",
    "docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "bridge", "awdl", "llw", "lo",
)

/**
 * Room for the whole sweep in the socket's send buffer. On Linux (so Android
 * too) a packet to an address that hasn't answered ARP waits in the kernel,
 * counted against this buffer, until ARP gives up (~3 s). With the default
 * (~220 KB) `send` blocked after ~150 such packets: a /22 sweep took 9 s on
 * the Pad Mini. The kernel caps this at `net.core.wmem_max` (8 MB there).
 */
private const val SEND_BUFFER_BYTES = 4 * 1024 * 1024

/** Keep listening this long after the last sweep packet goes out. */
private const val LISTEN_AFTER_SWEEP_MILLIS = 1_000L

/** Never search longer than this, however slow sending is. */
private const val MAX_SEARCH_MILLIS = 8_000L

/** Active private IPv4 networks that support broadcast (Wi-Fi/Ethernet). */
private fun localNetworks(): List<LocalNetwork> {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (e: IOException) {
        return emptyList()
    }
    return interfaces
        .filter { nic ->
            val name = nic.name.lowercase()
            skippedInterfaces.none { name.startsWith(it) } &&
                runCatching { nic.isUp && !nic.isLoopback && !nic.isVirtual && !nic.isPointToPoint }.getOrDefault(false)
        }
        .flatMap { it.interfaceAddresses }
        .mapNotNull { address ->
            val ip = (address.address as? Inet4Address)?.let(::toUInt) ?: return@mapNotNull null
            val broadcast = (address.broadcast as? Inet4Address)?.let(::toUInt) ?: return@mapNotNull null
            val prefix = address.networkPrefixLength.toInt()
            if (prefix !in 1..32 || !ServerDiscovery.isPrivate(ip)) return@mapNotNull null
            LocalNetwork(ip, UInt.MAX_VALUE shl (32 - prefix), broadcast)
        }
}

private fun toUInt(address: Inet4Address): UInt =
    address.address.fold(0u) { acc, byte -> acc shl 8 or (byte.toUInt() and 0xFFu) }

private fun toInet(ip: UInt): InetAddress =
    InetAddress.getByAddress(ByteArray(4) { ((ip shr (24 - it * 8)) and 0xFFu).toByte() })

/**
 * Listens from the start while the broadcast and sweep go out (on another
 * thread), until `timeoutMillis`, or [LISTEN_AFTER_SWEEP_MILLIS] after the
 * sweep ends if that's later; never past [MAX_SEARCH_MILLIS].
 */
internal actual suspend fun udpSearch(timeoutMillis: Long): List<DiscoveredServer> = withContext(Dispatchers.IO) {
    val found = mutableListOf<DiscoveredServer>()
    try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.sendBufferSize = SEND_BUFFER_BYTES
            // Short receive timeout, so the loop can check the deadline.
            socket.soTimeout = 250

            val start = System.currentTimeMillis()
            val sentAny = AtomicBoolean(false)
            val sweepEnd = AtomicLong(0) // 0 while still sending
            val sender = launch { sendAll(socket, sentAny, sweepEnd) }

            val buffer = ByteArray(8192)
            while (!socket.isClosed) {
                ensureActive()
                val now = System.currentTimeMillis()
                val sentAt = sweepEnd.get()
                if (sentAt != 0L && !sentAny.get()) break // nothing could be sent
                val deadline = if (sentAt == 0L) Long.MAX_VALUE
                else maxOf(start + timeoutMillis, sentAt + LISTEN_AFTER_SWEEP_MILLIS)
                if (now >= minOf(deadline, start + MAX_SEARCH_MILLIS)) break

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    // Often left over from the sweep (an address with no device); keep listening.
                    continue
                }
                val text = packet.data.decodeToString(packet.offset, packet.offset + packet.length)
                val sender = packet.address?.hostAddress.orEmpty()
                ServerDiscovery.parse(text, sender)?.let(found::add)
            }
            sender.cancel()
        }
    } catch (e: IOException) {
        // No socket allowed (no network): nothing found.
    }
    found
}

/** The broadcast, then the sweep. Sets `sweepEnd` when done. */
private fun CoroutineScope.sendAll(socket: DatagramSocket, sentAny: AtomicBoolean, sweepEnd: AtomicLong) {
    val message = ServerDiscovery.MESSAGE.encodeToByteArray()
    fun send(ip: UInt) {
        try {
            socket.send(DatagramPacket(message, message.size, toInet(ip), ServerDiscovery.PORT))
            sentAny.set(true)
        } catch (e: IOException) {
            // Unreachable address, or broadcasts not allowed: the rest still go.
        }
    }
    try {
        val networks = localNetworks()
        // Broadcast: everywhere, and on each network's own broadcast address
        // (some systems refuse 255.255.255.255 when several networks are up).
        (networks.map { it.broadcast } + UInt.MAX_VALUE).toSet().forEach(::send)

        // Sweep, with a short pause every 64 sends so the burst doesn't
        // overrun the network buffers.
        val targets = networks.flatMap { ServerDiscovery.sweepTargets(it.ip, it.mask) }.distinct().sorted()
        targets.forEachIndexed { index, target ->
            send(target)
            if (index % 64 == 63) {
                ensureActive()
                Thread.sleep(2)
            }
        }
    } finally {
        sweepEnd.set(System.currentTimeMillis())
    }
}
