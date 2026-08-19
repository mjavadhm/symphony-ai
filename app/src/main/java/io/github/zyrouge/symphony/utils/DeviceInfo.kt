package io.github.zyrouge.symphony.utils

import android.content.Context
import android.os.Build
import java.util.UUID

object DeviceInfo {
    private const val PREF = "device_info"
    private const val KEY_ID = "device_id"

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    // A stable UUID per install — no permission required
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }
}
