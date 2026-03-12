package org.carlogtt.plugins.icarus.actions

import com.intellij.openapi.components.Service
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
internal class IcarusCommandStateService {

    private val running = AtomicBoolean(false)
    private val runningTabTitle = AtomicReference<String?>(null)
    private val verboseEnabled = AtomicBoolean(false)

    fun tryAcquire(): Boolean {
        val acquired = running.compareAndSet(false, true)
        if (acquired) {
            runningTabTitle.set(null)
        }
        return acquired
    }

    fun setRunningTabTitle(tabTitle: String) {
        runningTabTitle.set(tabTitle)
    }

    fun runningTabTitle(): String? {
        return runningTabTitle.get()
    }

    fun release() {
        running.set(false)
        runningTabTitle.set(null)
    }

    fun isRunning(): Boolean {
        return running.get()
    }

    fun isVerboseEnabled(): Boolean {
        return verboseEnabled.get()
    }

    fun setVerboseEnabled(enabled: Boolean) {
        verboseEnabled.set(enabled)
    }
}
