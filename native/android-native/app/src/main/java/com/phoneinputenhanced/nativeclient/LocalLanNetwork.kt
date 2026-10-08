package com.phoneinputenhanced.nativeclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/** Selects a physical Wi-Fi/Ethernet route to the configured PC, outside a VPN default network. */
internal object LocalLanNetwork {
    fun forHost(context: Context, host: String): Network {
        val target = InetAddress.getByName(host)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.allNetworks
            .asSequence()
            .mapNotNull { candidate ->
                val capabilities = connectivity.getNetworkCapabilities(candidate) ?: return@mapNotNull null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    return@mapNotNull null
                }
                val prefixLength = connectivity.getLinkProperties(candidate)?.routes
                    ?.filter { it.matches(target) }
                    ?.maxOfOrNull { it.destination.prefixLength }
                prefixLength?.let { candidate to it }
            }
            .maxByOrNull { it.second }
            ?.first
            ?: throw IOException("No non-VPN LAN route to configured host")
    }

    fun openConnection(context: Context, host: String, url: URL): HttpURLConnection =
        forHost(context, host).openConnection(url) as HttpURLConnection

    fun createSocket(context: Context, host: String): Socket =
        forHost(context, host).socketFactory.createSocket()
}
