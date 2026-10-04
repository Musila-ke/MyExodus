package com.example.myexodus.data.db

import androidx.sqlite.SQLiteConnection

/**
 * Raw-SQL constraints Room's annotations cannot express. Applied in a RoomDatabase.Callback
 * onCreate, and re-applied by any future migration/destructive-recreate path that recreates
 * these tables. Idempotent: the rebuild is guarded by an sqlite_master check and runs inside
 * a SAVEPOINT (SQLite cannot ALTER-ADD a CHECK constraint, so restriction_rules is rebuilt).
 */
object SchemaConstraints {

    const val CHECK_SELF_BLOCK = "blocked_package_not_self"

    fun apply(connection: SQLiteConnection) {
        ensureRestrictionRuleChecks(connection)
        // v1-era ux_user_active (partial unique index on user_profile.is_active) is NOT created
        // here anymore: Room cannot model partial indexes in @Index, so a DB carrying one fails
        // post-migration TableInfo validation on every open (caught 2026-08-29 device spike run
        // 19: "Migration didn't properly handle: user_profile"). MIGRATION_1_2 drops it. Single-
        // active-profile is a profile-management concern (to be enforced deactivate-on-activate
        // in the V1 UI workstream); observeActive's ORDER BY user_id LIMIT 1 defines which
        // profile the engines consult, and getActiveRules() mirrors that pick exactly
        // (2026-10-04) so a stray second active row can never enforce rules the UI cannot show.
    }

    private fun ensureRestrictionRuleChecks(connection: SQLiteConnection) {
        val alreadyApplied = connection.prepare(
            "SELECT count(*) FROM sqlite_master " +
                "WHERE type = 'table' AND name = 'restriction_rules' AND sql LIKE '%$CHECK_SELF_BLOCK%'",
        ).use { stmt ->
            stmt.step()
            stmt.getLong(0) > 0
        }
        if (alreadyApplied) return

        exec(connection, "SAVEPOINT apply_schema_constraints")
        try {
            exec(connection, "DROP TABLE IF EXISTS restriction_rules_rebuilt;")
            exec(
                connection,
                """
                CREATE TABLE restriction_rules_rebuilt (
                    rule_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    blocked_package TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    is_always_on INTEGER NOT NULL,
                    daily_limit_ms INTEGER NOT NULL,
                    time_range_start INTEGER,
                    time_range_end INTEGER,
                    active_days_of_week TEXT NOT NULL,
                    timezone TEXT NOT NULL DEFAULT 'UTC',
                    block_expires_at INTEGER NOT NULL,
                    severity_level TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(rule_id),
                    FOREIGN KEY(profile_id) REFERENCES user_profile(user_id),
                    CONSTRAINT blocked_package_not_self
                        CHECK (TRIM(LOWER(blocked_package)) != 'com.example.myexodus'),
                    CONSTRAINT severity_valid
                        CHECK (severity_level IN ('LOW','MEDIUM','HIGH','CRITICAL'))
                );
                """.trimIndent(),
            )
            exec(
                connection,
                """
                INSERT INTO restriction_rules_rebuilt
                    (rule_id, profile_id, blocked_package, target_type, is_always_on, daily_limit_ms,
                     time_range_start, time_range_end, active_days_of_week, timezone, block_expires_at,
                     severity_level, is_enabled)
                SELECT rule_id, profile_id, blocked_package, target_type, is_always_on, daily_limit_ms,
                       time_range_start, time_range_end, active_days_of_week, timezone, block_expires_at,
                       severity_level, is_enabled
                FROM restriction_rules;
                """.trimIndent(),
            )
            exec(connection, "DROP TABLE restriction_rules;")
            exec(connection, "ALTER TABLE restriction_rules_rebuilt RENAME TO restriction_rules;")
            // DROP TABLE removed Room's generated unique index; recreate it.
            exec(
                connection,
                "CREATE UNIQUE INDEX IF NOT EXISTS index_restriction_rules_profile_id_blocked_package " +
                    "ON restriction_rules (profile_id, blocked_package);",
            )
            exec(connection, "RELEASE apply_schema_constraints;")
        } catch (e: Throwable) {
            exec(connection, "ROLLBACK TO apply_schema_constraints;")
            exec(connection, "RELEASE apply_schema_constraints;")
            throw e
        }
    }

    private fun exec(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }
}
