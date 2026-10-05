package com.freezr.app.ui.freeze

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.Decision
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.apps.AppIconCache
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.platform.usage.UsageTracker
import com.freezr.app.ui.MainActivity
import com.freezr.app.ui.components.AppCatalog
import com.freezr.app.ui.components.ProvideAppCatalog
import com.freezr.app.ui.theme.FreezrTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fallback block screen used only if the accessibility overlay window cannot be added.
 * singleInstance + excludeFromRecents (see manifest). It re-derives its own decision from the
 * snapshot, so it closes itself as soon as the app is no longer frozen.
 */
@AndroidEntryPoint
class FreezeActivity : ComponentActivity() {
    @Inject lateinit var stateRepo: FreezeStateRepository
    @Inject lateinit var time: TimeSource
    @Inject lateinit var apps: InstalledAppsRepository
    @Inject lateinit var icons: AppIconCache
    @Inject lateinit var usage: UsageTracker

    private var model by mutableStateOf<FreezeScreenModel?>(null)
    private var target: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_PACKAGE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        val catalog = object : AppCatalog {
            override fun label(packageName: String) = apps.label(packageName)
            override fun peekIcon(packageName: String) = icons.peek(packageName)
            override suspend fun loadIcon(packageName: String) = icons.load(packageName)
        }
        setContent {
            FreezrTheme(darkTheme = true) {
                ProvideAppCatalog(catalog) {
                    model?.let { m ->
                        FreezeContent(
                            model = m,
                            zone = time.zone(),
                            nowProvider = time::now,
                            onGoHome = ::goHome,
                            onEmergency = {
                                startActivity(MainActivity.emergencyIntent(this, m.packageName))
                                finish()
                            },
                            onExpired = ::refresh,
                        )
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                stateRepo.snapshot.filterNotNull().collect { refresh() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        target = intent.getStringExtra(EXTRA_PACKAGE)
        refresh()
    }

    private fun refresh() {
        val pkg = target ?: return finish()
        val base = stateRepo.snapshot.value ?: return
        val now = time.now()
        val ctx = base.copy(usageToday = usage.liveUsage(now))
        when (val d = FreezeDecisionEngine.evaluate(pkg, now, time.zone(), ctx)) {
            Decision.Allowed -> finish()
            is Decision.Frozen -> model = FreezeScreenModel(
                pkg, apps.label(pkg), d.reason, d.ruleName, d.unfreezeAt, d.canEmergencyUnlock, ctx.emergencyPassesRemaining,
            )
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, FreezeActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
