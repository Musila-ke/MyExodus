package com.example.myexodus.account

/** Shared account-test identities and seams (plan §12.1). */

internal val accountGuestUser =
    AuthIdentity("guest-1", isAnonymous = true, email = null, emailVerified = false, providerIds = emptySet())
internal val accountMemberUser =
    AuthIdentity("member-1", isAnonymous = false, email = "user@example.com", emailVerified = true, providerIds = setOf("password"))

internal class FakeAuthOps(initial: AuthIdentity?) : AuthOperations {
    var identity: AuthIdentity? = initial
    private val listeners = mutableListOf<(AuthIdentity?) -> Unit>()
    var signInCalls = 0
    var signInGoogleCalls = 0
    var linkGoogleCalls = 0

    fun become(next: AuthIdentity?) {
        identity = next
        listeners.toList().forEach { it(next) }
    }

    override fun currentIdentity(): AuthIdentity? = identity

    override fun observeIdentity(onChange: (AuthIdentity?) -> Unit): () -> Unit {
        listeners += onChange
        return { listeners -= onChange }
    }

    override suspend fun createWithEmail(email: String, password: String): AuthIdentity =
        throw UnsupportedOperationException()

    override suspend fun signInWithEmail(email: String, password: String): AuthIdentity {
        signInCalls++
        return accountMemberUser.copy(email = email).also { identity = it }
    }

    override suspend fun linkWithEmail(email: String, password: String): AuthIdentity =
        throw UnsupportedOperationException()

    override suspend fun signInWithGoogle(idToken: String): AuthIdentity {
        signInGoogleCalls++
        return accountMemberUser.also { identity = it }
    }

    override suspend fun linkWithGoogle(idToken: String): AuthIdentity {
        linkGoogleCalls++
        return accountMemberUser.also { identity = it }
    }

    override suspend fun sendVerificationEmail(): AuthIdentity =
        throw UnsupportedOperationException()

    override suspend fun sendPasswordReset(email: String) = Unit

    override suspend fun reloadAndRefreshToken(): AuthIdentity =
        identity ?: throw UnsupportedOperationException()

    override suspend fun reauthenticateWithEmail(email: String, password: String) = Unit

    override suspend fun reauthenticateWithGoogle(idToken: String) = Unit

    override suspend fun signOut() {
        identity = null
    }
}

/** In-memory [GuestQuarantine] with the production record semantics the callers rely on. */
internal class FakeQuarantine : GuestQuarantine {
    val records = mutableListOf<GuestQuarantineRecord>()
    var saveResult = true
    var markResult = true
    var sweepRemoved = 0
    var unreadable = 0
    val bound = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<Pair<String, String>>()
    var deleteAllCalls = 0

    override suspend fun save(record: GuestQuarantineRecord): Boolean {
        if (!saveResult) return false
        records.removeAll { it.guestUid == record.guestUid && it.transitionId == record.transitionId }
        records += record
        return true
    }

    override suspend fun load(guestUid: String): QuarantineLoad =
        records.filter { it.guestUid == guestUid }.maxByOrNull { it.stagedAtMs }
            ?.let { QuarantineLoad.Present(it) }
            ?: QuarantineLoad.Absent

    override suspend fun forTarget(targetUid: String) =
        QuarantineScan(records.filter { it.targetUid == targetUid }, unreadable = unreadable)

    override suspend fun markFinalized(
        guestUid: String,
        transitionId: String,
        targetUid: String,
    ): Boolean {
        if (!markResult) return false
        bound += transitionId to targetUid
        for (i in records.indices) {
            val record = records[i]
            if (record.guestUid == guestUid && record.transitionId == transitionId) {
                records[i] = record.copy(targetUid = targetUid)
            }
        }
        return true
    }

    override suspend fun deleteFor(guestUid: String, transitionId: String) {
        deleted += guestUid to transitionId
        records.removeAll { it.guestUid == guestUid && it.transitionId == transitionId }
    }

    override suspend fun deleteAll() {
        deleteAllCalls++
        records.clear()
    }

    override suspend fun sweepExpired(nowMs: Long): Int = sweepRemoved
}

internal class FakeStager : GuestDataStager {
    var result = true
    val calls = mutableListOf<Triple<String, String, TransitionIntent>>()

    override suspend fun stage(
        guestUid: String,
        transitionId: String,
        intent: TransitionIntent,
    ): Boolean {
        calls += Triple(guestUid, transitionId, intent)
        return result
    }
}
