package io.github.zyrouge.symphony

import android.app.Application
import android.os.Process
import io.github.zyrouge.symphony.services.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class SymphonyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Diagnostics.initialize(this) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handling = AtomicBoolean(false)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handling.compareAndSet(false, true)) {
                runCatching { Diagnostics.crash(error) }
                if (!getProcessName().endsWith(":crash_report")) runCatching { ErrorActivity.start(this, error) }
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
    }
}
