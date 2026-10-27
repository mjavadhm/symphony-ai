package io.github.zyrouge.symphony.utils

import android.util.Log
import io.github.zyrouge.symphony.services.diagnostics.Diagnostics
import io.github.zyrouge.symphony.services.diagnostics.DiagnosticPrivacy

object Logger {
    private const val TAG = "SymphonyLogger"
    // Persist stable categories + message-free stack frames, never arbitrary caller text.
    fun warn(mod: String, text: String): Int {
        Diagnostics.event("WARN", mod, "logged_warning")
        return Log.w(TAG, "${DiagnosticPrivacy.token(mod)}: warning")
    }
    fun warn(mod: String, text: String, throwable: Throwable): Int {
        Diagnostics.event("WARN", mod, "logged_warning", throwable)
        return Log.w(TAG, "${DiagnosticPrivacy.token(mod)}: ${DiagnosticPrivacy.throwable(throwable)}")
    }
    fun error(mod: String, text: String): Int {
        Diagnostics.event("ERROR", mod, "logged_error")
        return Log.e(TAG, "${DiagnosticPrivacy.token(mod)}: error")
    }
    fun error(mod: String, text: String, throwable: Throwable): Int {
        Diagnostics.event("ERROR", mod, "logged_error", throwable)
        return Log.e(TAG, "${DiagnosticPrivacy.token(mod)}: ${DiagnosticPrivacy.throwable(throwable)}")
    }
    fun joinTextThrowable(text: String, throwable: Throwable) = DiagnosticPrivacy.throwable(throwable)
}
