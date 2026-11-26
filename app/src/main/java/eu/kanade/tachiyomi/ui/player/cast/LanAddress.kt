package eu.kanade.tachiyomi.ui.player.cast

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Picks the phone's LAN address for URLs a cast receiver fetches from the phone, when the receiver
 * connection does not tell us (the Google Cast SDK path). The first non-loopback address is often the
 * wrong one: VPNs (Tailscale's 100.64/10, `tun0`) and mobile data (`rmnet*`) come first on many phones.
 */
object LanAddress {

    data class Candidate(val interfaceName: String, val address: String, val prefixLength: Int)

    fun pick(candidates: List<Candidate>): Candidate? = candidates
        .filter { isPrivateIpv4(it.address) }
        .minByOrNull { rank(it.interfaceName) }

    fun current(): Candidate? = pick(candidates())

    fun candidates(): List<Candidate> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { intf ->
                intf.interfaceAddresses.mapNotNull { address ->
                    val inet = address.address as? Inet4Address ?: return@mapNotNull null
                    Candidate(
                        intf.name,
                        inet.hostAddress ?: return@mapNotNull null,
                        address.networkPrefixLength.toInt(),
                    )
                }
            }
    }.getOrDefault(emptyList())

    private val wifiPrefixes = listOf("wlan", "wifi")
    private val wiredPrefixes = listOf("eth", "en")
    private val tunnelOrMobilePrefixes = listOf("tun", "rmnet", "ccmni", "tailscale")

    private fun rank(name: String): Int = when {
        wifiPrefixes.any(name::startsWith) -> 0
        wiredPrefixes.any(name::startsWith) -> 1
        tunnelOrMobilePrefixes.any(name::startsWith) -> 3
        else -> 2
    }

    /** RFC 1918 private ranges: where home Wi-Fi lives. */
    fun isPrivateIpv4(address: String): Boolean {
        val octets = address.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (a, b) = octets
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
