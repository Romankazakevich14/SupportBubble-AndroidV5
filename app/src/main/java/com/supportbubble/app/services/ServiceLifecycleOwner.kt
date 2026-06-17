package com.supportbubble.app.services

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A minimal [SavedStateRegistryOwner] + [LifecycleOwner] + [ViewModelStoreOwner] that
 * enables [androidx.compose.ui.platform.ComposeView] to be hosted inside a
 * [android.app.Service] or any other non-Activity context that adds views via
 * [android.view.WindowManager].
 *
 * Implementing [ViewModelStoreOwner] here is required because that interface exposes a
 * `viewModelStore` property and therefore cannot be created via a SAM lambda — a real
 * implementation must own the store.
 *
 * Usage:
 * ```
 * val owner = ServiceLifecycleOwner().also { it.onCreate(); it.onResume() }
 * composeView.setViewTreeLifecycleOwner(owner)
 * composeView.setViewTreeViewModelStoreOwner(owner)
 * composeView.setViewTreeSavedStateRegistryOwner(owner)
 * ```
 *
 * Call [onDestroy] when the hosting view is removed to release Compose state and clear
 * the [ViewModelStore].
 */
class ServiceLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
