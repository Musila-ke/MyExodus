package com.example.myexodus.update

import com.example.myexodus.BuildConfig

/** Plan §11: one release-build constant shared with the publisher script. Never server-supplied. */
internal const val MAX_UPDATE_APK_BYTES = 250L * 1024 * 1024

internal object UpdateConfig {
    /** Owner/repo of the dedicated public releases-only repository (plan §8.2). */
    val releaseRepository: String = BuildConfig.UPDATE_RELEASE_REPOSITORY.trim().trim('/')

    const val MANIFEST_FORMAT = "myexodus-update-envelope-v1"
    const val MANIFEST_KEY_ID = "primary-v1"
    const val CHANNEL = "stable"
    const val MANIFEST_ASSET = "update-manifest.json"

    /**
     * Pinned production verification key (ECDSA P-256 SPKI, base64). The private half lives
     * offline at ~/.myexodus-update-key/update-manifest-key.pem and never enters the repo.
     */
    const val MANIFEST_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfYH/XoT6frxw1dK//zZqNulq5jp3GjwCmutzsJVI+bLVrKNTc1RpuSpUDapFqq1SzsvkG4WV0Y7M8HhxkjvGzA=="

    const val MAX_ENVELOPE_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 16 * 1024
    const val MAX_APK_BYTES = MAX_UPDATE_APK_BYTES
    const val MAX_RELEASE_NOTES_CHARS = 4000
    const val MAX_VERSION_NAME_CHARS = 64

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000
    const val OVERALL_TIMEOUT_MS = 30_000
    const val MAX_REDIRECTS = 5

    const val CHECK_INTERVAL_HOURS = 24L
    const val CHECK_FLEX_HOURS = 2L
    const val MANUAL_THROTTLE_MS = 5L * 60 * 1000
    const val CHECK_STALE_MS = 7L * 24 * 60 * 60 * 1000
    const val INSTALL_UI_ALLOWANCE_MS = 10L * 60 * 1000

    val configured: Boolean
        get() = releaseRepository.isNotBlank() && MANIFEST_PUBLIC_KEY_BASE64.isNotBlank()

    val manifestUrl: String
        get() = "https://github.com/$releaseRepository/releases/latest/download/$MANIFEST_ASSET"

    val releasePageUrl: String
        get() = "https://github.com/$releaseRepository/releases/latest"

    /** Metadata fetches may follow GitHub's HTTPS redirects only (plan §8.2). */
    fun allowedRedirectHost(host: String): Boolean =
        host == "github.com" || host.endsWith(".githubusercontent.com")
}
