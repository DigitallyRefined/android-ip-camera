package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import androidx.preference.PreferenceManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The interface every listener (web, RTSP, ONVIF) binds to, from the "listen_interface" setting.
 * [DEFAULT] listens on all interfaces; any other value is a network interface name, resolved to
 * that interface's current address each time a server starts.
 */
object ListenInterface {
    const val PREF = "listen_interface"
    const val DEFAULT = "0.0.0.0"

    /**
     * The configured interface name (or [DEFAULT] for all interfaces), normalized so values
     * saved by older versions—where this was a free-text IP address—keep working.
     */
    fun value(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(PREF, DEFAULT)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT
        val normalized = normalize(raw)
        if (normalized != raw) prefs.edit().putString(PREF, normalized).apply()
        return normalized
    }

    /**
     * Maps an arbitrary saved value to one of the selectable options. Interface names are kept as
     * is; an assigned IP address is translated to its interface name so it follows DHCP changes;
     * anything stale or invalid falls back to listening on all interfaces.
     */
    fun normalize(raw: String): String {
        if (raw == DEFAULT) return DEFAULT
        val namedInterface = runCatching { NetworkInterface.getByName(raw) }.getOrNull()
        if (namedInterface != null && parse(raw) != null) return raw
        val address = parse(raw) ?: return DEFAULT
        if (address.isAnyLocalAddress) return DEFAULT
        val networkInterface =
            runCatching { NetworkInterface.getByInetAddress(address) }.getOrNull() ?: return DEFAULT
        return networkInterface.name
    }

    /** Selectable options: all interfaces first, then every interface that currently has an address. */
    fun options(): List<Pair<String, String>> = buildList {
        add("All interfaces" to DEFAULT)
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { it.name }
        for (networkInterface in interfaces) {
            val addresses = networkInterface.inetAddresses.toList().filter { !it.isLoopbackAddress }
            if (addresses.isEmpty()) continue
            val shown = addresses.firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }
                ?: addresses.firstOrNull { !it.isLinkLocalAddress }
                ?: addresses.first()
            add("${networkInterface.name} (${shown.hostAddress})" to networkInterface.name)
        }
    }

    /**
     * Resolves the setting to a bindable address. Accepts an IP address, a hostname, or a network
     * interface name (binding to its first address). Returns null when it cannot be resolved.
     */
    fun parse(value: String): InetAddress? {
        val text = value.trim()
        if (text.isEmpty()) return null
        try {
            return InetAddress.getByName(text)
        } catch (_: Exception) {
            // Not an address/hostname: maybe an interface name such as "wlan0".
        }
        return try {
            val addresses = NetworkInterface.getByName(text)?.inetAddresses?.toList()
                ?.filter { !it.isLoopbackAddress }
                .orEmpty()
            // Prefer a routable IPv4 address so "wlan0" does not pick a link-local IPv6 one.
            addresses.firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }
                ?: addresses.firstOrNull { !it.isLinkLocalAddress }
                ?: addresses.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Bind address for the servers, defaulting to all interfaces when the setting is invalid. */
    fun resolve(context: Context): InetAddress =
        parse(value(context)) ?: InetAddress.getByName(DEFAULT)

    /** True when [address] is a wildcard or is currently assigned to one of the device's interfaces. */
    fun isAssigned(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)

    fun isAssigned(value: String): Boolean = parse(value)?.let(::isAssigned) == true

    /** Non-loopback addresses currently assigned to the device, for error messages and suggestions. */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    fun interfaceNames(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .map { it.name }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /** Explains why a configured address cannot be bound, listing alternatives. */
    fun unavailableMessage(value: String): String {
        val addresses = localAddresses()
        val names = interfaceNames()
        return buildString {
            append("Listen address ").append(value).append(" is not assigned to this device.")
            if (addresses.isNotEmpty()) append(" Available addresses: ").append(addresses.joinToString(", ")).append('.')
            if (names.isNotEmpty()) {
                append(" Use an interface name (").append(names.joinToString(", "))
                    .append(") to follow its current address automatically.")
            }
        }
    }

    /** Non-wildcard IPv4 address the servers are limited to, or null when listening on all. */
    fun specificIpv4(context: Context): String? {
        val address = resolve(context)
        return (address as? Inet4Address)?.takeIf { !it.isAnyLocalAddress }?.hostAddress
    }
}
