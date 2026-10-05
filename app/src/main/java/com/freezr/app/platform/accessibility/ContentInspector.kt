package com.freezr.app.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.freezr.app.domain.model.FreezeReason
import kotlinx.coroutines.flow.Flow

/** What to do when an inspector matches something inside an allowed app's window. */
sealed interface ContentVerdict {
    /** Press back (leave the surface) and briefly explain why. */
    data class LeaveSurface(val reason: FreezeReason, val label: String, val recordAttempt: Boolean = true) : ContentVerdict

    /** Show the full block screen for a website, offering "Go back". */
    data class BlockSite(val domain: String, val ruleName: String) : ContentVerdict
}

/**
 * Inspects window content of specific packages (browsers, apps with in-app rules, Settings under
 * Strict Mode). Inspectors must FAIL OPEN: return null unless they positively matched a selector.
 * Implementations never log, store or transmit screen content.
 */
interface ContentInspector {
    /** True while this inspector needs window-content events at all (drives the service config). */
    val active: Flow<Boolean>

    /** Cheap check run on every event before any node traversal. */
    fun interestedIn(packageName: String): Boolean

    fun inspect(packageName: String, root: AccessibilityNodeInfo): ContentVerdict?
}
