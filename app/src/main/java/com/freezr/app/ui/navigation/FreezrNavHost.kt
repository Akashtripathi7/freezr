package com.freezr.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.freezr.app.R
import com.freezr.app.ui.apps.AppsScreen
import com.freezr.app.ui.emergency.EmergencyScreen
import com.freezr.app.ui.health.OemGuideScreen
import com.freezr.app.ui.home.HomeNav
import com.freezr.app.ui.home.HomeScreen
import com.freezr.app.ui.insights.InsightsScreen
import com.freezr.app.ui.onboarding.OnboardingScreen
import com.freezr.app.ui.rules.ContextRuleEditScreen
import com.freezr.app.ui.rules.ContextRulesScreen
import com.freezr.app.ui.rules.SelectorHealthScreen
import com.freezr.app.ui.rules.WebBlockingScreen
import com.freezr.app.ui.schedules.ScheduleEditScreen
import com.freezr.app.ui.schedules.SchedulesScreen
import com.freezr.app.ui.settings.PrivacyScreen
import com.freezr.app.ui.settings.SettingsScreen
import com.freezr.app.ui.settings.WhitelistScreen
import com.freezr.app.ui.strict.StrictScreen

object Routes {
    const val HOME = "home"
    const val SCHEDULES = "schedules"
    const val APPS = "apps"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val SCHEDULE_EDIT = "schedule/{id}"
    const val ONBOARDING = "onboarding"
    const val OEM = "oem"
    const val STRICT = "strict"
    const val EMERGENCY = "emergency?pkg={pkg}"
    const val WHITELIST = "whitelist"
    const val RULES = "rules"
    const val RULE_EDIT = "rule/{id}"
    const val WEB = "web"
    const val SELECTORS = "selectors"
    const val PRIVACY = "privacy"
    const val HEALTH = "health"

    fun schedule(id: Long) = "schedule/$id"
    fun rule(id: Long) = "rule/$id"
    fun emergency(pkg: String? = null) = if (pkg == null) "emergency" else "emergency?pkg=$pkg"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, R.string.tab_home, Icons.Rounded.Home),
    Tab(Routes.SCHEDULES, R.string.tab_schedules, Icons.Rounded.Bedtime),
    Tab(Routes.APPS, R.string.tab_apps, Icons.Rounded.Apps),
    Tab(Routes.INSIGHTS, R.string.tab_insights, Icons.Rounded.BarChart),
)

@Composable
fun FreezrNavHost(onboardingComplete: Boolean, pendingRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBar = TABS.any { it.route == current }

    LaunchedEffect(pendingRoute) {
        val r = pendingRoute ?: return@LaunchedEffect
        if (onboardingComplete) {
            if (r == Routes.HEALTH) nav.navigateTab(Routes.HOME) else nav.navigate(r) { launchSingleTop = true }
        }
        onRouteConsumed()
    }

    Scaffold(
        // Each screen handles its own top insets; the shell only reserves room for the bottom bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val bottom = PaddingValues(bottom = if (showBar) padding.calculateBottomPadding() else 0.dp)
        Box(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
            NavHost(
                navController = nav,
                startDestination = if (onboardingComplete) Routes.HOME else Routes.ONBOARDING,
                enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(onFinished = {
                        nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    })
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        HomeNav(
                            onSettings = { nav.navigate(Routes.SETTINGS) },
                            onSchedules = { nav.navigateTab(Routes.SCHEDULES) },
                            onNewSchedule = { nav.navigate(Routes.schedule(0)) },
                            onApps = { nav.navigateTab(Routes.APPS) },
                            onStrict = { nav.navigate(Routes.STRICT) },
                            onEmergencyInfo = { nav.navigate(Routes.emergency()) },
                            onRules = { nav.navigate(Routes.RULES) },
                            onWeb = { nav.navigate(Routes.WEB) },
                            onOemGuide = { nav.navigate(Routes.OEM) },
                        ),
                    )
                }
                composable(Routes.SCHEDULES) {
                    SchedulesScreen(
                        onCreate = { nav.navigate(Routes.schedule(0)) },
                        onEdit = { nav.navigate(Routes.schedule(it)) },
                        onOpenStrict = { nav.navigate(Routes.STRICT) },
                    )
                }
                composable(Routes.SCHEDULE_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    ScheduleEditScreen(onBack = { nav.popBackStack() }, onOpenStrict = { nav.navigate(Routes.STRICT) })
                }
                composable(Routes.APPS) { AppsScreen(onOpenStrict = { nav.navigate(Routes.STRICT) }) }
                composable(Routes.INSIGHTS) { InsightsScreen() }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onWhitelist = { nav.navigate(Routes.WHITELIST) },
                        onStrict = { nav.navigate(Routes.STRICT) },
                        onOem = { nav.navigate(Routes.OEM) },
                        onPrivacy = { nav.navigate(Routes.PRIVACY) },
                        onSelectors = { nav.navigate(Routes.SELECTORS) },
                        onRules = { nav.navigate(Routes.RULES) },
                        onWeb = { nav.navigate(Routes.WEB) },
                        onEmergency = { nav.navigate(Routes.emergency()) },
                    )
                }
                composable(Routes.OEM) { OemGuideScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.STRICT) { StrictScreen(onBack = { nav.popBackStack() }) }
                composable(
                    Routes.EMERGENCY,
                    arguments = listOf(navArgument("pkg") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) {
                    EmergencyScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
                }
                composable(Routes.WHITELIST) { WhitelistScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.RULES) {
                    ContextRulesScreen(
                        onBack = { nav.popBackStack() },
                        onEdit = { nav.navigate(Routes.rule(it)) },
                    )
                }
                composable(Routes.RULE_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    ContextRuleEditScreen(onBack = { nav.popBackStack() })
                }
                composable(Routes.WEB) { WebBlockingScreen(onBack = { nav.popBackStack() }, onSelectors = { nav.navigate(Routes.SELECTORS) }) }
                composable(Routes.SELECTORS) { SelectorHealthScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
