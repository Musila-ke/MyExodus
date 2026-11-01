package com.example.myexodus.accountability.sync

/**
 * Substitution point for the two-AVD rig (debug source set only — the same shape as
 * [AppCheckInstaller.factoryOverride]). The bundled Firestore emulator keeps explicit nulls
 * out of its index, so an equality-on-null filter matches nothing there (verified against
 * firebase-tools 15.9.0: `whereEqualTo(field, null)` returns 0 for a document whose field is
 * explicitly null, while the same query matches in production, where equality-on-null is the
 * documented way to find explicit nulls). [FirestoreSchema.nudgeInboxQuery] omits those two
 * filters when this is false; [com.example.myexodus.accountability.sos.NudgeInboxRepository]
 * filters the same documents client-side, so the visible behavior is identical. Set to false
 * only by the debug bootstrap when it wires the emulator; always true in release.
 */
object EmulatorCompat {
    @Volatile
    var matchesExplicitNulls: Boolean = true
}
