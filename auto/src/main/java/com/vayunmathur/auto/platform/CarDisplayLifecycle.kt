package com.vayunmathur.auto.platform

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Lifecycle plumbing for the car [Presentation] on its private virtual display.
 *
 * A `Presentation` has no activity lifecycle, which is why the car surface was
 * Views-only. This owner supplies the three `ViewTree` owners Compose needs
 * (`LifecycleOwner`, `ViewModelStoreOwner`, `SavedStateRegistryOwner`) and is
 * driven manually: created alongside the `VirtualDisplay`, stepped to RESUMED
 * when the presentation shows, and torn down with it. The video/input path
 * (`VirtualDisplay` -> encoder surface, vsync drain, decorView touch dispatch)
 * is untouched; only the hierarchy inside becomes a `ComposeView`.
 */
class CarDisplayLifecycle :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Steps CREATED -> STARTED -> RESUMED. Main thread only. */
    fun moveToResumed() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** Steps RESUMED -> DESTROYED and clears the store. Main thread only. */
    fun moveToDestroyed() {
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
        runCatching { store.clear() }
    }
}
