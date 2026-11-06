package com.example.myexodus.update

internal object UpdateConfig {
    const val BASE_URL = "https://github.com/Musila-ke/MyExodus/releases/latest/download/"
    const val MANIFEST_ASSET = "update-manifest.json"

    /**
     * SPKI DER base64 of the offline manifest signing key. The private key never ships; an
     * empty value makes the whole channel fail closed instead of trusting GitHub's transport.
     */
    const val MANIFEST_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfYH/XoT6frxw1dK//zZqNulq5jp3GjwCmutzsJVI+bLVrKNTc1RpuSpUDapFqq1SzsvkG4WV0Y7M8HhxkjvGzA=="

    const val CHECK_INTERVAL_HOURS = 24L
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val STAGED_APK_TTL_MS = 7L * 24 * 60 * 60 * 1000
}
