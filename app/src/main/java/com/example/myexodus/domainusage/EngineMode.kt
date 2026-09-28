package com.example.myexodus.domainusage

/**
 * Engine operating mode (FR-11). `OFF` and `ACCOUNT` never shape, block, or drop anything;
 * only `ENFORCE` performs the FR-8 enforcement actions. Configured via
 * `BuildConfig.DOMAIN_USAGE_MODE` (debug `ENFORCE`, release `OFF`); debug builds additionally
 * honor the DataStore override in [DomainUsageSettings].
 */
enum class EngineMode {
    OFF,
    ACCOUNT,
    ENFORCE;

    companion object {
        /** Null for anything unrecognized — callers decide the fallback (never a silent ENFORCE). */
        fun parseOrNull(raw: String?): EngineMode? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

        /**
         * S1.3 resolution order: debug-only DataStore override, then the build-configured
         * value, then fail safe to `OFF`.
         */
        fun effectiveMode(buildConfigValue: String?, debugBuild: Boolean, override: String?): EngineMode =
            (if (debugBuild) parseOrNull(override) else null)
                ?: parseOrNull(buildConfigValue)
                ?: OFF
    }
}
