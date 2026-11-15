package com.example.myexodus.accountability.delivery

import android.content.Context
import android.util.Log
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.blocking.EnhancedModeTestEntryPoint
import com.example.myexodus.data.db.LocalDatabaseMigration
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only oracle for the delivery pipeline: dumps the latest outbox rows and every
 * installation row so a stuck event's terminal state is inspected, never guessed.
 * Writes nothing.
 *
 *   adb -s <serial> shell am instrument -w \
 *     -e class com.example.myexodus.accountability.delivery.PartnerOutboxDiagnosticsInstrumentedTest \
 *     com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PartnerOutboxDiagnosticsInstrumentedTest {

    @Test
    fun dumpOutboxAndInstallations(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // MyExodusApp.onCreate skips the scope decision under instrumentation (MyExodusApp.kt:286)
        // and the Hilt graph may build the DB before this test runs, binding the LEGACY file name.
        // Open the same uid-scoped file the live app writes, directly.
        LocalDatabaseMigration.start(context)
        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            LocalDatabaseMigration.accountabilityDbName(context),
        )
            .addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS)
            .build()

        db.useConnection(isReadOnly = true) { conn ->
            conn.usePrepared(
                "SELECT event_id, owner_uid, installation_id, kind, status, attempts, last_error_code, " +
                    "created_local_at_ms, next_attempt_at_ms, lease_until_ms, terminal_at_ms, recipient_pair_id, " +
                    "wire_outcome, acked_server_at_ms, length(signed_envelope) AS signed_len " +
                    "FROM pending_partner_events ORDER BY created_local_at_ms DESC LIMIT 15",
            ) { c ->
                while (c.step()) {
                    Log.i(TAG, "OUTBOX " + (0 until c.getColumnCount()).joinToString(" ") {
                        "${c.getColumnName(it)}=${if (c.isNull(it)) "null" else c.getText(it)}"
                    })
                }
            }
            conn.usePrepared(
                "SELECT installation_id, owner_uid, registration_state, current_sequence " +
                    "FROM installation_state",
            ) { c ->
                while (c.step()) {
                    Log.i(TAG, "INSTALL " + (0 until c.getColumnCount()).joinToString(" ") {
                        "${c.getColumnName(it)}=${if (c.isNull(it)) "null" else c.getText(it)}"
                    })
                }
            }
        }

        val dao = db.serviceEventOutboxDao()
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        Log.i(TAG, "AUTH uid=$uid")
        if (uid != null) {
            Log.i(TAG, "earliestNextAttempt(uid)=${dao.earliestNextAttempt(uid)}")
            Log.i(
                TAG,
                "dueRows(uid)=${dao.dueRows(uid, System.currentTimeMillis(), 10).map { it.eventId }}",
            )
        }
        db.close()

        // The rules store is SQLCipher-encrypted on this install, so it can only be opened
        // through the DI factory (which carries the vault DEK). A null here means the factory
        // was already bound to the legacy file during MyExodusApp.onCreate.
        Log.i(TAG, "RULES name=${LocalDatabaseMigration.rulesDbName(context)}")
        val store = EntryPointAccessors
            .fromApplication(context, EnhancedModeTestEntryPoint::class.java)
            .appRulesStoreFactory()
            .open()
        if (store == null) {
            Log.i(TAG, "RULES store=null (factory bound to legacy file or vault locked)")
        } else {
            store.useConnection(isReadOnly = true) { conn ->
                conn.usePrepared(
                    "SELECT event_id, kind, target_pair_id, occurred_at_ms, created_at_ms " +
                        "FROM partner_activity_outbox ORDER BY created_at_ms DESC LIMIT 10",
                ) { c ->
                    while (c.step()) {
                        Log.i(TAG, "JOURNAL " + (0 until c.getColumnCount()).joinToString(" ") {
                            "${c.getColumnName(it)}=${if (c.isNull(it)) "null" else c.getText(it)}"
                        })
                    }
                }
            }
            store.useConnection(isReadOnly = true) { conn ->
                conn.usePrepared("SELECT event_id, pair_id, sequence FROM partner_activity_link") { c ->
                    while (c.step()) {
                        Log.i(TAG, "LINK " + (0 until c.getColumnCount()).joinToString(" ") {
                            "${c.getColumnName(it)}=${if (c.isNull(it)) "null" else c.getText(it)}"
                        })
                    }
                }
            }
            Log.i(TAG, "RULES linkedPairs=${store.partnerActivityDao().linkedPairIds()}")
        }
    }

    private companion object {
        const val TAG = "MyExodusPartnerTest"
    }
}
