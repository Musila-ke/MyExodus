package com.example.myexodus.accountability.enrollment

/**
 * The §19.5 target rule, shared by the dashboard, the SOS sender, the recipient inbox, and the
 * block switch (mapping doc D8): the persisted pick when it names a live enrollment, else the
 * most recently activated. Every server path re-verifies membership independently, so a stale
 * pick can only produce a clean refusal — never a misdelivery.
 */
fun List<EnrollmentRecord>.targetEnrollment(selectedPairId: String?): EnrollmentRecord? =
    firstOrNull { it.pairId == selectedPairId } ?: maxByOrNull { it.createdAtEpochMillis }
