package com.freezr.app.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.freezr.app.ui.components.AppCatalog
import com.freezr.app.ui.components.ProvideAppCatalog
import com.freezr.app.ui.freeze.FreezeContent
import com.freezr.app.ui.freeze.FreezeScreenModel
import com.freezr.app.ui.freeze.PipCover
import com.freezr.app.ui.theme.FreezrTheme
import java.time.Instant
import java.time.ZoneId

/**
 * Draws the block screen as a TYPE_ACCESSIBILITY_OVERLAY window owned by the accessibility service
 * (no SYSTEM_ALERT_WINDOW permission needed). One window is reused: showing a different app just
 * swaps the model, so repeated events never stack or flicker.
 */
class OverlayController(
    private val context: Context,
    private val catalog: AppCatalog,
    private val zone: () -> ZoneId,
    private val now: () -> Instant,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onGoHome(model: FreezeScreenModel)
        fun onEmergency(model: FreezeScreenModel)
        fun onExpired(model: FreezeScreenModel)
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val model = mutableStateOf<FreezeScreenModel?>(null)
    private var root: View? = null
    private var pipRoot: View? = null
    private var owner: OverlayOwner? = null
    private var pipOwner: OverlayOwner? = null

    val isShowing: Boolean get() = root != null
    val current: FreezeScreenModel? get() = model.value

    /** Returns false if the window could not be added (caller falls back to FreezeActivity). */
    fun show(m: FreezeScreenModel): Boolean {
        model.value = m
        if (root != null) return true
        val o = OverlayOwner().also { it.start() }
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FreezrTheme(darkTheme = true) {
                    ProvideAppCatalog(catalog) {
                        val current = model.value
                        if (current != null) {
                            FreezeContent(
                                model = current,
                                zone = zone(),
                                nowProvider = now,
                                onGoHome = { callbacks.onGoHome(current) },
                                onEmergency = { callbacks.onEmergency(current) },
                                onExpired = { callbacks.onExpired(current) },
                            )
                        }
                    }
                }
            }
        }
        val container = BackInterceptingLayout(context) { model.value?.let(callbacks::onGoHome) }.apply {
            addView(compose)
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            wm.addView(container, params)
            container.requestFocus()
            root = container
            owner = o
            true
        } catch (_: RuntimeException) { // BadTokenException / SecurityException / IllegalStateException
            o.destroy()
            model.value = null
            false
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        owner?.destroy()
        root = null
        owner = null
        model.value = null
    }

    /** Covers only the PiP window's bounds; touches elsewhere pass through. */
    fun showPipCover(bounds: Rect) {
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        val existing = pipRoot
        if (existing != null) {
            runCatching { wm.updateViewLayout(existing, params) }
            return
        }
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent { FreezrTheme(darkTheme = true) { PipCover() } }
        }
        runCatching { wm.addView(v, params) }.onSuccess {
            pipRoot = v
            pipOwner = o
        }.onFailure { o.destroy() }
    }

    fun hidePipCover() {
        pipRoot?.let { runCatching { wm.removeViewImmediate(it) } }
        pipOwner?.destroy()
        pipRoot = null
        pipOwner = null
    }

    fun destroy() {
        hide()
        hidePipCover()
    }

    /** Focusable container so the back key reaches us; back acts like "Go Home". */
    private class BackInterceptingLayout(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** Minimal lifecycle/saved-state owner so Compose can run inside a service-owned window. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun start() {
            savedState.performRestore(Bundle())
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
