package com.example.myexodus.applock

/**
 * Plan §4.2. Only feature status, envelope versions and the migration journal are durable;
 * UI unlock state lives in memory and a fresh process always starts in a non-unlocked state.
 */
enum class AppLockState {
    DISABLED,
    SETUP_REQUIRED,
    ENROLLING,
    LOCKED,
    PREFLIGHT,
    AUTHENTICATING,
    OPENING,
    UNLOCKED,
    LOCKING,
    DISABLING,
    RECOVERY_REQUIRED;

    /** §8.2: the protected tree exists only for an unlocked vault or a feature that is off. */
    val showsProtectedUi: Boolean
        get() = this == DISABLED || this == UNLOCKED
}
