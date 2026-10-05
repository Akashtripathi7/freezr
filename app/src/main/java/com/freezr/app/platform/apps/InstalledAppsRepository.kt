package com.freezr.app.platform.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.freezr.app.di.ApplicationScope
import com.freezr.app.di.OwnPackage
import com.freezr.app.data.repo.ProtectedPackagesSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

sealed interface PackageChange {
    val packageName: String
    data class Added(override val packageName: String) : PackageChange
    data class Removed(override val packageName: String) : PackageChange
    data class Changed(override val packageName: String) : PackageChange
}

/**
 * Launchable apps, discovered with a launcher-intent <queries> declaration (no QUERY_ALL_PACKAGES).
 * Re-queries whenever a package is added / removed / changed.
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    private val context: Context,
    protectedSource: ProtectedPackagesSource,
    @OwnPackage private val ownPackage: String,
    @ApplicationScope scope: CoroutineScope,
) {
    private val labelCache = HashMap<String, String>()

    /** Hot stream of package-change broadcasts (registered while anyone listens). */
    val packageChanges: SharedFlow<PackageChange> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> trySend(if (replacing) PackageChange.Changed(pkg) else PackageChange.Added(pkg))
                    Intent.ACTION_PACKAGE_REMOVED -> if (!replacing) trySend(PackageChange.Removed(pkg))
                    else -> trySend(PackageChange.Changed(pkg))
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }.shareIn(scope, SharingStarted.WhileSubscribed(5_000))

    /** Every launchable app except ours, sorted by label. Includes protected apps; see [blockableApps]. */
    val allApps: StateFlow<List<InstalledApp>> = packageChanges
        .map { }
        .onStart { emit(Unit) }
        .map { loadLaunchable() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(10_000), emptyList())

    /** Launchable apps the user may freeze (system-critical / core-protected apps removed). */
    val blockableApps: StateFlow<List<InstalledApp>> = combine(allApps, protectedSource.corePackages) { apps, core ->
        apps.filter { it.packageName !in core }
    }.stateIn(scope, SharingStarted.WhileSubscribed(10_000), emptyList())

    fun label(packageName: String): String = synchronized(labelCache) {
        labelCache.getOrPut(packageName) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() })
        }
    }

    fun isInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0); true }.getOrDefault(false)

    private fun loadLaunchable(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val collator = Collator.getInstance()
        val apps = infos.asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ownPackage }
            .map { ai ->
                val label = ai.loadLabel(pm).toString()
                synchronized(labelCache) { labelCache[ai.packageName] = label }
                InstalledApp(ai.packageName, label, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }
            .toList()
        return apps.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
}
