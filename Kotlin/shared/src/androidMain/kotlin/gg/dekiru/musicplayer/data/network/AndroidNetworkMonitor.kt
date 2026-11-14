package gg.dekiru.musicplayer.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * The default network through `ConnectivityManager`: online when it can
 * reach the internet, metered unless the system says it isn't (mobile
 * data, hotspots). Lives as long as the app.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor(initialState(context)) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    update(stateOf(capabilities))
                }

                override fun onLost(network: Network) {
                    update(NetworkState(online = false, metered = false))
                }
            })
        }
    }

    private companion object {
        fun initialState(context: Context): NetworkState = runCatching {
            val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            manager.getNetworkCapabilities(manager.activeNetwork)?.let(::stateOf) ?: NetworkState(online = false)
        }.getOrDefault(NetworkState())

        fun stateOf(capabilities: NetworkCapabilities) = NetworkState(
            online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}
