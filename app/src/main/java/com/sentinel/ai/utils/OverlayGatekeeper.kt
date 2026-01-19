package com.sentinel.ai.utils

import java.util.Collections
import java.util.WeakHashMap

/**
 * Keeps weak references to overlay controllers so we can dismiss all overlays
 * when a sensitive app (e.g., banking) is foreground.
 */
object OverlayGatekeeper {
    private val controllers = Collections.newSetFromMap(WeakHashMap<OverlayController, Boolean>())

    fun register(controller: OverlayController) {
        controllers.add(controller)
    }

    fun dismissAll() {
        controllers.forEach { it.dismiss(force = false) }
    }
}
