package com.freezr.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.platform.EnforcementCoordinator
import com.freezr.app.platform.apps.AppIconCache
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.ui.components.AppCatalog
import com.freezr.app.ui.components.ProvideAppCatalog
import com.freezr.app.ui.navigation.FreezrNavHost
import com.freezr.app.ui.theme.FreezrTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var icons: AppIconCache
    @Inject lateinit var apps: InstalledAppsRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var coordinator: EnforcementCoordinator

    /** Deep-link route requested by a notification / overlay / tile; consumed by the nav host. */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        // Opening the app is one of the places we detect a dead / disabled service (matrix row 8).
        coordinator.reconcileAsync("app_opened")

        val catalog = object : AppCatalog {
            override fun label(packageName: String) = apps.label(packageName)
            override fun peekIcon(packageName: String) = icons.peek(packageName)
            override suspend fun loadIcon(packageName: String) = icons.load(packageName)
        }
        val onboarded = settings.settings.map { it.onboardingComplete }

        setContent {
            FreezrTheme {
                ProvideAppCatalog(catalog) {
                    val done by onboarded.collectAsState(initial = null)
                    val route by pendingRoute.collectAsState()
                    // Freeze the start destination at its first known value; finishing onboarding
                    // navigates explicitly instead of rebuilding the graph.
                    var start by remember { mutableStateOf<Boolean?>(null) }
                    if (start == null && done != null) start = done
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        start?.let { complete ->
                            FreezrNavHost(
                                onboardingComplete = complete,
                                pendingRoute = route,
                                onRouteConsumed = { pendingRoute.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"

        fun routeIntent(context: Context, route: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun emergencyIntent(context: Context, packageName: String): Intent =
            routeIntent(context, "emergency?pkg=$packageName")
    }
}
