package com.phoneinputenhanced.nativeclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Watches Wi-Fi/Ethernet reachability, not Internet validation. Local-LAN use must keep working without WAN. */
class NetworkLifecycleMonitor(
    context: Context,
    private val onAvailable: () -> Unit,
    private val onLost: () -> Unit,
) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val availableNetworks = mutableSetOf<Network>()
    private var registered = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(availableNetworks) { availableNetworks += network }
            onAvailable()
        }

        override fun onLost(network: Network) {
            val empty = synchronized(availableNetworks) {
                availableNetworks -= network
                availableNetworks.isEmpty()
            }
            if (empty) onLost()
        }
    }

    fun start() {
        if (registered) return
        registered = true
        manager.registerNetworkCallback(request, callback)
        val current = manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@filter false
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        synchronized(availableNetworks) {
            availableNetworks.clear()
            availableNetworks.addAll(current)
        }
        if (current.isEmpty()) onLost() else onAvailable()
    }

    fun isLanAvailable(): Boolean = synchronized(availableNetworks) { availableNetworks.isNotEmpty() }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { manager.unregisterNetworkCallback(callback) }
        synchronized(availableNetworks) { availableNetworks.clear() }
    }
}
