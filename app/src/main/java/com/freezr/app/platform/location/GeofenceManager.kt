package com.freezr.app.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.freezr.app.data.repo.ContextRuleRepository
import com.freezr.app.platform.PlatformEntryPoint
import com.freezr.app.platform.goAsyncWithTimeout
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers geofences for enabled location rules and reconciles the persisted "inside" state from the
 * last known location. Idempotent; called on boot, app update, service start, watchdog and rule edits.
 * Degrades to "rule never active" (with a health-card warning) if location permission is missing.
 */
@Singleton
class GeofenceManager @Inject constructor(
    private val context: Context,
    private val rules: ContextRuleRepository,
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    fun hasPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    @SuppressLint("MissingPermission") // checked by hasPermissions()
    suspend fun sync() {
        val fences = rules.geofences()
        if (!hasPermissions()) {
            // Can't observe location: treat every location rule as "outside" rather than guessing.
            rules.setActive(fences.map { it.id }, false)
            return
        }
        try {
            client.removeGeofences(pendingIntent()).await()
            if (fences.isEmpty()) return
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(
                    fences.map { r ->
                        Geofence.Builder()
                            .setRequestId(r.id.toString())
                            .setCircularRegion(r.latitude!!, r.longitude!!, r.radiusMeters ?: DEFAULT_RADIUS)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                            .build()
                    },
                )
                .build()
            client.addGeofences(request, pendingIntent()).await()
            reconcileFromLastLocation()
        } catch (e: Exception) {
            Log.w(TAG, "geofence sync failed", e)
        }
    }

    /** Recompute inside/outside directly from the last known fix (covers missed transitions). */
    @SuppressLint("MissingPermission")
    private suspend fun reconcileFromLastLocation() {
        val last: Location = runCatching { LocationServices.getFusedLocationProviderClient(context).lastLocation.await() }.getOrNull() ?: return
        val fences = rules.geofences()
        val inside = fences.filter { r ->
            val out = FloatArray(1)
            Location.distanceBetween(last.latitude, last.longitude, r.latitude!!, r.longitude!!, out)
            out[0] <= (r.radiusMeters ?: DEFAULT_RADIUS) + last.accuracy.coerceAtMost(100f) / 2
        }.map { it.id }
        rules.setActive(inside, true)
        rules.setActive(fences.map { it.id } - inside.toSet(), false)
    }

    suspend fun onEvent(intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence error ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            // GEOFENCE_NOT_AVAILABLE: location turned off / Play services reset; fences were dropped.
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                rules.setActive(rules.geofences().map { it.id }, false)
            }
            return
        }
        val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toLongOrNull() }
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> rules.setActive(ids, true)
            Geofence.GEOFENCE_TRANSITION_EXIT -> rules.setActive(ids, false)
        }
    }

    // Geofencing requires a mutable PendingIntent: Play services fills in the event extras.
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )

    private companion object {
        const val TAG = "FreezrGeofence"
        const val DEFAULT_RADIUS = 150f
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun geofences(): GeofenceManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        goAsyncWithTimeout {
            EntryPointAccessors.fromApplication(app, Deps::class.java).geofences().onEvent(intent)
            EntryPointAccessors.fromApplication(app, PlatformEntryPoint::class.java).coordinator().replan()
        }
    }
}
