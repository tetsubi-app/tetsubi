package gg.dekiru.musicplayer.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The device's connection: any route at all, and whether it's metered (mobile data, hotspot). */
data class NetworkState(val online: Boolean = true, val metered: Boolean = false)

/**
 * Watches the network (as `NetworkMonitor` on iOS): drives the offline
 * banner, offline Home, the mobile-data stream cap and downloads waiting
 * for Wi‑Fi. Android: `AndroidNetworkMonitor`; the desktop is always
 * online and unmetered for now. [update] is for the platform's callbacks
 * (and tests).
 */
open class NetworkMonitor(initial: NetworkState = NetworkState()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    val isOnline: Boolean get() = _state.value.online
    val isMetered: Boolean get() = _state.value.metered

    fun update(state: NetworkState) {
        _state.value = state
    }
}
