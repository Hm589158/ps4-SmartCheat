package com.ps4smartcheat.core.protocol

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Local-network-only policy: ps4debug has no authentication and must never be used across the internet. */
object NetworkPolicy {
    fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
            isCgnat(address)
        is Inet6Address -> address.isLinkLocalAddress || address.isSiteLocalAddress || address.isLoopbackAddress ||
            (address.address[0].toInt() and 0xFE) == 0xFC // fc00::/7 unique local
        else -> false
    }

    private fun isCgnat(a: Inet4Address): Boolean {
        val b = a.address
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64 // 100.64.0.0/10
    }

    /** Validates a user-supplied host string without resolving DNS names (DNS is resolved at connect time). */
    fun validateHostSyntax(host: String): String? {
        val h = host.trim()
        if (h.isEmpty()) return "Enter the PS4 IP address"
        if (h.length > 253) return "Host name is too long"
        if (h.any { it.isWhitespace() }) return "Host must not contain spaces"
        return null
    }

    fun validatePort(port: Int): String? = if (port in 1..65535) null else "Port must be between 1 and 65535"
}
