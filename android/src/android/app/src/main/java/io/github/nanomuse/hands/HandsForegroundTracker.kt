package io.github.nanomuse.hands

/** Records MainActivity's actual resumed state so a launch request is not mistaken for a return. */
internal object HandsForegroundTracker {
    @Volatile private var resumed = false

    fun isResumed(): Boolean = resumed

    fun onResumed() {
        resumed = true
    }

    fun onPaused() {
        resumed = false
    }
}
