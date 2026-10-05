package com.freezr.app.platform.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.freezr.app.data.repo.ContextRuleRepository
import com.freezr.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the connected Wi-Fi SSID with a NetworkCallback and persists each Wi-Fi rule's "connected"
 * state. Reading the SSID requires location permission (and location enabled); without it every
 * Wi-Fi rule stays inactive and the health card explains why.
 */
@Singleton
class WifiMonitor @Inject constructor(
    private val context: Context,
    private val rules: ContextRuleRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var registered = false

    private val callback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(caps)
                override fun onLost(network: Network) = update(null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(caps)
                override fun onLost(network: Network) = update(null)
            }
        }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Idempotent: registers once per process and immediately reconciles the current SSID. */
    fun start() {
        reconcileNow()
        if (registered) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm?.registerNetworkCallback(request, callback) }.onSuccess { registered = true }
    }

    fun reconcileNow() = update(currentSsid())

    @Suppress("DEPRECATION") // connectionInfo is still the only synchronous SSID read on all API levels.
    private fun currentSsid(): String? {
        if (!hasLocation()) return null
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return wifi.connectionInfo?.ssid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }

    private fun onCaps(caps: NetworkCapabilities) {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.transportInfo as? WifiInfo else null
        val ssid = info?.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID } ?: currentSsid()
        update(ssid)
    }

    private fun update(ssid: String?) {
        scope.launch { rules.onWifiChanged(if (hasLocation()) ssid else null) }
    }
}
