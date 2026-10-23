package io.github.nanomuse.hands

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/** Read-only evidence. Absence of a grant does not identify who removed it. */
object HandsAccessibilityHealth {
    private const val PREFS = "hands.accessibility_diagnostics"

    fun classify(granted: Boolean?, connected: Boolean): String = when {
        granted == null -> "grant_unknown"
        !granted -> "permission_disabled"
        !connected -> "enabled_disconnected"
        else -> "connected"
    }

    @Synchronized fun lifecycle(context: Context, event: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = JSONArray(prefs.getString("events", "[]"))
            val next = JSONArray()
            for (i in maxOf(0, old.length() - 15) until old.length()) next.put(old.get(i))
            next.put(JSONObject().put("event", event).put("at", System.currentTimeMillis())
                .put("elapsed_ms", SystemClock.elapsedRealtime()))
            prefs.edit().putString("events", next.toString()).apply()
        }
    }

    fun snapshot(context: Context): JSONObject {
        val granted = runCatching {
            !AccessibilityRecoveryManager.isRevokedIn(
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                context.packageName, MinisAccessibilityService::class.java.name,
            )
        }.getOrNull()
        val svc = MinisAccessibilityService.getInstance()
        val connected = svc?.handsConnected == true
        val out = JSONObject().put("at", System.currentTimeMillis())
            .put("permission_enabled", granted ?: JSONObject.NULL)
            .put("service_instance_present", svc != null).put("service_connected", connected)
            .put("state", classify(granted, connected))
            .put("cause", "requires_correlated_device_evidence")
        runCatching {
            out.put("lifecycle", JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("events", "[]")))
        }
        // Android records process exits even when a killed process cannot run onDestroy.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            out.put("process_exits", JSONArray().apply {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { exit ->
                    put(JSONObject().put("at", exit.timestamp).put("reason", exit.reason)
                        .put("status", exit.status).put("importance", exit.importance))
                }
            })
        }.onFailure { out.put("process_exit_read_error", it.javaClass.simpleName) }
        return out
    }
}
