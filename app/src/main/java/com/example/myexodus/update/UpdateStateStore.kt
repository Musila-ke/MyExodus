package com.example.myexodus.update

import android.content.Context

internal enum class InstallStatus {
    PENDING,
    SUCCEEDED,
    FAILED_ABORTED,
    FAILED_BLOCKED,
    FAILED_CONFLICT,
    FAILED_INCOMPATIBLE,
    FAILED_INVALID,
    FAILED_STORAGE,
    FAILED_TIMEOUT,
    FAILED_UNKNOWN,
}

internal class UpdateStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheckAtMs(): Long = prefs.getLong(KEY_LAST_CHECK_AT, 0L)

    fun availableEnvelope(): String? = prefs.getString(KEY_ENVELOPE, null)

    fun lastFailure(): UpdateFailure? = prefs.getString(KEY_LAST_FAILURE, null)
        ?.let { runCatching { UpdateFailure.valueOf(it) }.getOrNull() }

    fun clearAvailable() {
        prefs.edit().remove(KEY_ENVELOPE).apply()
    }

    fun recordCheck(result: UpdateCheckResult, now: Long) {
        val editor = prefs.edit().putLong(KEY_LAST_CHECK_AT, now)
        when (result) {
            is UpdateCheckResult.Available -> editor
                .putString(KEY_ENVELOPE, result.envelopeJson)
                .remove(KEY_LAST_FAILURE)
            UpdateCheckResult.UpToDate, UpdateCheckResult.NotConfigured -> editor
                .remove(KEY_ENVELOPE)
                .remove(KEY_LAST_FAILURE)
            is UpdateCheckResult.Failure -> editor.putString(KEY_LAST_FAILURE, result.reason.name)
        }
        editor.apply()
    }

    fun installStatus(): InstallStatus? = prefs.getString(KEY_INSTALL_STATUS, null)
        ?.let { runCatching { InstallStatus.valueOf(it) }.getOrNull() }

    fun installStartedAtMs(): Long = prefs.getLong(KEY_INSTALL_STARTED, 0L)

    fun recordInstallStart(now: Long) {
        prefs.edit()
            .putString(KEY_INSTALL_STATUS, InstallStatus.PENDING.name)
            .putLong(KEY_INSTALL_STARTED, now)
            .apply()
    }

    fun recordInstallStatus(status: InstallStatus) {
        prefs.edit().putString(KEY_INSTALL_STATUS, status.name).apply()
    }

    fun clearInstallStatus() {
        prefs.edit().remove(KEY_INSTALL_STATUS).remove(KEY_INSTALL_STARTED).apply()
    }

    /** True when this launch is the first after an upgrade; the caller shows the notice. */
    fun recordLaunch(installedVersionCode: Int): Boolean {
        val last = prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0)
        prefs.edit().putInt(KEY_LAST_SEEN_VERSION_CODE, installedVersionCode).apply()
        return last != 0 && installedVersionCode > last
    }

    private companion object {
        const val PREFS = "update_state"
        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val KEY_ENVELOPE = "envelope"
        const val KEY_LAST_FAILURE = "last_failure"
        const val KEY_INSTALL_STATUS = "install_status"
        const val KEY_INSTALL_STARTED = "install_started"
        const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    }
}
