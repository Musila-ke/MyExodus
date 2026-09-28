package com.example.myexodus.domainusage

/**
 * Engine status exposed to the UI (FR-9, §8 S9.4). `Degraded` reasons are exactly the three
 * the plan defines; the banner copy for each is in S9.
 */
sealed interface EngineStatus {

    data object Dormant : EngineStatus

    data object Active : EngineStatus

    data class Degraded(val reason: Reason) : EngineStatus

    enum class Reason { PRIVATE_DNS, DB_ERROR, INTERNAL_ERROR }
}
