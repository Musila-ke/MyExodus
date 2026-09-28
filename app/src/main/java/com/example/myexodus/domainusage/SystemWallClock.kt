package com.example.myexodus.domainusage

import java.time.LocalDateTime
import java.time.ZoneId

/** The only class in this package allowed to read wall time (S1.2); CI enforces that. */
class SystemWallClock : WallClock {
    override fun now(zone: ZoneId): LocalDateTime = LocalDateTime.now(zone)
}
