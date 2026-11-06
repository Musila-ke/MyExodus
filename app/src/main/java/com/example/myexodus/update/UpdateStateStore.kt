package com.example.myexodus.update

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.security.SecureRandom

internal enum class CheckStatus { IDLE, CHECKING, SUCCESS, FAILED }

internal enum class DisplayStatus {
    UP_TO_DATE,
    AVAILABLE,
    HALTED,
    NOT_IN_ROLLOUT,
    POLICY_ROLLBACK,
    CHECK_STALE,
}

internal enum class AttemptState {
    DOWNLOAD_STARTING,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    NEEDS_INSTALL_PERMISSION,
    STAGING,
    INSTALLING,
    AWAITING_USER_CONFIRMATION,
    UPDATE_STATUS_UNKNOWN,
    SUCCEEDED,
    CANCELLED,
    FAILED;

    val active: Boolean
        get() = this != SUCCEEDED && this != CANCELLED && this != FAILED
}

/**
 * Plan §9 outcome codes. Raw platform codes never reach the UI. Public because
 * [com.example.myexodus.ui.settings.UpdateUiState] carries it.
 */
enum class AttemptError {
    NO_STABLE_POLICY,
    NETWORK_UNAVAILABLE,
    NETWORK_TIMEOUT,
    HOST_THROTTLED,
    HOST_UNAVAILABLE,
    POLICY_INVALID,
    POLICY_ROLLBACK,
    POLICY_CONFLICT,
    DOWNLOAD_SERVICE_UNAVAILABLE,
    DOWNLOAD_DESTINATION_UNAVAILABLE,
    DOWNLOAD_FAILED,
    APK_INTEGRITY_FAILURE,
    RELEASE_INCOMPATIBLE,
    INSUFFICIENT_STORAGE,
    NEEDS_INSTALL_PERMISSION,
    INSTALL_UI_BLOCKED,
    INSTALL_BLOCKED,
    INSTALL_VERIFICATION_BLOCKED,
    INSTALL_CONFLICT,
    INSTALL_TIMEOUT,
    WAITING_FOR_POLICY_CONFIRMATION,
    UPDATE_STATUS_UNKNOWN,
    PROTECTION_RESUME_REQUIRED,
    HALTED,
    NOT_IN_ROLLOUT,
    INSTALL_FAILED,
}

internal data class UpdateAttempt(
    val attemptId: String,
    val state: AttemptState,
    val policySequence: Long,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSha256Hex: String,
    val apkSizeBytes: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val downloadId: Long? = null,
    val destination: String? = null,
    val sessionId: Int? = null,
    val meteredConsent: Boolean = false,
    val committed: Boolean = false,
    val error: AttemptError? = null,
    val userActionIntentUri: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("attemptId", attemptId)
        put("state", state.name)
        put("policySequence", policySequence)
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("apkUrl", apkUrl)
        put("apkSha256Hex", apkSha256Hex)
        put("apkSizeBytes", apkSizeBytes)
        put("minSdk", minSdk)
        put("targetSdk", targetSdk)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
        put("meteredConsent", meteredConsent)
        put("committed", committed)
        downloadId?.let { put("downloadId", it) }
        destination?.let { put("destination", it) }
        sessionId?.let { put("sessionId", it) }
        error?.let { put("error", it.name) }
        userActionIntentUri?.let { put("userActionIntentUri", it) }
    }.toString()

    companion object {
        fun fromJson(raw: String): UpdateAttempt? = runCatching {
            val obj = JSONObject(raw)
            UpdateAttempt(
                attemptId = obj.getString("attemptId"),
                state = AttemptState.valueOf(obj.getString("state")),
                policySequence = obj.getLong("policySequence"),
                versionCode = obj.getLong("versionCode"),
                versionName = obj.getString("versionName"),
                apkUrl = obj.getString("apkUrl"),
                apkSha256Hex = obj.getString("apkSha256Hex"),
                apkSizeBytes = obj.getLong("apkSizeBytes"),
                minSdk = obj.optInt("minSdk", 0),
                targetSdk = obj.optInt("targetSdk", 0),
                createdAtMs = obj.getLong("createdAtMs"),
                updatedAtMs = obj.getLong("updatedAtMs"),
                downloadId = obj.optLong("downloadId", -1).takeIf { it >= 0 },
                destination = obj.optString("destination").takeIf { it.isNotBlank() },
                sessionId = obj.optInt("sessionId", -1).takeIf { it >= 0 },
                meteredConsent = obj.optBoolean("meteredConsent", false),
                committed = obj.optBoolean("committed", false),
                error = obj.optString("error").takeIf { it.isNotBlank() }
                    ?.let { runCatching { AttemptError.valueOf(it) }.getOrNull() },
                userActionIntentUri = obj.optString("userActionIntentUri").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}

internal sealed interface FloorResult {
    data object Accepted : FloorResult
    data object Rollback : FloorResult
    data object Conflict : FloorResult
}

/**
 * One prefs file holds both orthogonal records (plan §8.3). Every state-critical write is
 * synchronous (KTX `edit(commit = true)`) so an attempt or floor is durable before the next
 * platform step begins. apply() would let process death lose the write the plan requires.
 */
@SuppressLint("ApplySharedPref")
internal class UpdateStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cohortId(): ByteArray {
        prefs.getString(KEY_COHORT_ID, null)
            ?.let(::hexToBytes)
            ?.takeIf { it.size == COHORT_ID_BYTES }
            ?.let { return it }
        val fresh = ByteArray(COHORT_ID_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit(commit = true) { putString(KEY_COHORT_ID, fresh.toHex()) }
        return fresh
    }

    fun highestPolicySequence(): Long = prefs.getLong(KEY_HIGHEST_SEQUENCE, 0L)

    fun highestVersionCode(): Long = prefs.getLong(KEY_HIGHEST_VERSION, 0L)

    /** Initialize the release floor from the installed package; never lowers it. */
    fun ensureVersionFloor(installedVersionCode: Long) {
        if (highestVersionCode() < installedVersionCode) {
            prefs.edit(commit = true) { putLong(KEY_HIGHEST_VERSION, installedVersionCode) }
        }
    }

    fun recordVerifiedPolicy(
        policy: UpdatePolicy,
        payloadHash: String,
        installedVersionCode: Long,
    ): FloorResult {
        val highestSequence = highestPolicySequence()
        val highestHash = prefs.getString(KEY_HIGHEST_HASH, null)
        if (policy.policySequence < highestSequence) return FloorResult.Rollback
        if (policy.policySequence == highestSequence &&
            highestHash != null &&
            highestHash != payloadHash
        ) {
            return FloorResult.Conflict
        }
        val nextVersion = maxOf(
            highestVersionCode(),
            installedVersionCode,
            policy.release?.versionCode ?: 0L,
        )
        prefs.edit(commit = true) {
            putLong(KEY_HIGHEST_VERSION, nextVersion)
            if (policy.policySequence > highestSequence) {
                putLong(KEY_HIGHEST_SEQUENCE, policy.policySequence)
                putString(KEY_HIGHEST_HASH, payloadHash)
            }
        }
        return FloorResult.Accepted
    }

    fun latestEnvelope(): String? = prefs.getString(KEY_LATEST_ENVELOPE, null)

    fun checkStatus(): CheckStatus = prefs.getString(KEY_CHECK_STATUS, null)
        ?.let { runCatching { CheckStatus.valueOf(it) }.getOrNull() }
        ?: CheckStatus.IDLE

    fun displayStatus(): DisplayStatus? = prefs.getString(KEY_DISPLAY_STATUS, null)
        ?.let { runCatching { DisplayStatus.valueOf(it) }.getOrNull() }

    fun lastCheckAtMs(): Long = prefs.getLong(KEY_LAST_CHECK_AT, 0L)

    fun lastSuccessAtMs(): Long = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L)

    fun lastCheckError(): AttemptError? = prefs.getString(KEY_LAST_CHECK_ERROR, null)
        ?.let { runCatching { AttemptError.valueOf(it) }.getOrNull() }

    fun markChecking(now: Long) {
        prefs.edit {
            putString(KEY_CHECK_STATUS, CheckStatus.CHECKING.name)
            putLong(KEY_LAST_CHECK_AT, now)
        }
    }

    fun recordCheckSuccess(now: Long, display: DisplayStatus, envelopeJson: String) {
        prefs.edit {
            putString(KEY_CHECK_STATUS, CheckStatus.SUCCESS.name)
            putString(KEY_DISPLAY_STATUS, display.name)
            putString(KEY_LATEST_ENVELOPE, envelopeJson)
            putLong(KEY_LAST_SUCCESS_AT, now)
            remove(KEY_LAST_CHECK_ERROR)
        }
    }

    fun recordCheckFailure(failure: AttemptError, now: Long) {
        prefs.edit {
            putString(KEY_CHECK_STATUS, CheckStatus.FAILED.name)
            putString(KEY_LAST_CHECK_ERROR, failure.name)
            putLong(KEY_LAST_CHECK_AT, now)
        }
    }

    fun recordPolicyRejection(failure: AttemptError, now: Long) {
        prefs.edit {
            putString(KEY_CHECK_STATUS, CheckStatus.FAILED.name)
            putString(KEY_DISPLAY_STATUS, DisplayStatus.POLICY_ROLLBACK.name)
            putString(KEY_LAST_CHECK_ERROR, failure.name)
            putLong(KEY_LAST_CHECK_AT, now)
        }
    }

    fun activeAttempt(): UpdateAttempt? = prefs.getString(KEY_ATTEMPT, null)
        ?.let(UpdateAttempt::fromJson)

    fun saveAttempt(attempt: UpdateAttempt) {
        prefs.edit(commit = true) { putString(KEY_ATTEMPT, attempt.toJson()) }
    }

    fun clearAttempt() {
        prefs.edit(commit = true) { remove(KEY_ATTEMPT) }
    }

    /** True when this launch is the first after an upgrade; the caller shows the notice. */
    fun recordLaunch(installedVersionCode: Long): Boolean {
        // Pre-1.1.2 builds stored this as an Int; a bare getLong crashes the process on the
        // first launch after upgrading from one (rehearsal, X6532 2026-11-07).
        val last = when (val stored = prefs.all[KEY_LAST_SEEN_VERSION_CODE]) {
            is Long -> stored
            is Int -> stored.toLong()
            else -> 0L
        }
        prefs.edit { putLong(KEY_LAST_SEEN_VERSION_CODE, installedVersionCode) }
        return last != 0L && installedVersionCode > last
    }

    fun protectionResumptionRequired(): Boolean =
        prefs.getBoolean(KEY_PROTECTION_RESUME_REQUIRED, false)

    fun setProtectionResumptionRequired(required: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_PROTECTION_RESUME_REQUIRED, required) }
    }

    /** Standing consent for background updates (plan revision 2026-11-06). Default off. */
    fun autoDownloadEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)

    fun setAutoDownloadEnabled(enabled: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_AUTO_DOWNLOAD, enabled) }
    }

    /** Reminder de-duplication: one available notification per offered versionCode. */
    fun lastAvailableNotifiedVersionCode(): Long = prefs.getLong(KEY_LAST_NOTIFIED_VERSION, 0L)

    fun markAvailableNotified(versionCode: Long) {
        prefs.edit(commit = true) { putLong(KEY_LAST_NOTIFIED_VERSION, versionCode) }
    }

    /** One-time in-app prompt per offered versionCode, independent of the notification. */
    fun availablePromptShownVersionCode(): Long = prefs.getLong(KEY_PROMPT_SHOWN_VERSION, 0L)

    fun markAvailablePromptShown(versionCode: Long) {
        prefs.edit { putLong(KEY_PROMPT_SHOWN_VERSION, versionCode) }
    }

    private companion object {
        const val PREFS = "update_state"
        const val KEY_COHORT_ID = "cohort_id"
        const val KEY_HIGHEST_SEQUENCE = "highest_policy_sequence"
        const val KEY_HIGHEST_HASH = "highest_policy_hash"
        const val KEY_HIGHEST_VERSION = "highest_version_code"
        const val KEY_LATEST_ENVELOPE = "latest_envelope"
        const val KEY_CHECK_STATUS = "check_status"
        const val KEY_DISPLAY_STATUS = "display_status"
        const val KEY_LAST_CHECK_AT = "last_check_at"
        const val KEY_LAST_SUCCESS_AT = "last_success_at"
        const val KEY_LAST_CHECK_ERROR = "last_check_error"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
        const val KEY_PROTECTION_RESUME_REQUIRED = "protection_resume_required"
        const val KEY_AUTO_DOWNLOAD = "auto_download"
        const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        const val KEY_PROMPT_SHOWN_VERSION = "prompt_shown_version"

        const val COHORT_ID_BYTES = 16
    }
}

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    return runCatching {
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()
}
