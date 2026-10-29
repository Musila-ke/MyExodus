package com.example.myexodus.account

/** The signed-in identity as the coordinator needs it; no Firebase type leaks past this seam. */
data class AuthIdentity(
    val uid: String,
    val isAnonymous: Boolean,
    val email: String?,
    val emailVerified: Boolean,
    val providerIds: Set<String>,
) {
    val providers: Set<AuthProvider>
        get() = buildSet {
            if (!isAnonymous) {
                if ("password" in providerIds) add(AuthProvider.PASSWORD)
                if ("google.com" in providerIds) add(AuthProvider.GOOGLE)
            }
        }
}

/**
 * Account plan §4.1: the Firebase seam behind [AuthCoordinator]. It is an interface so the
 * coordinator's state machine is unit-testable without Firebase (plan §12.1); the production
 * implementation is [FirebaseAuthOperations]. Failures arrive as [AuthOperationException] with
 * a mapped, generic [AuthFailure].
 */
interface AuthOperations {
    fun currentIdentity(): AuthIdentity?

    /** Auth-state callback (main thread). Returns the remover. */
    fun observeIdentity(onChange: (AuthIdentity?) -> Unit): () -> Unit

    suspend fun createWithEmail(email: String, password: String): AuthIdentity
    suspend fun signInWithEmail(email: String, password: String): AuthIdentity
    suspend fun linkWithEmail(email: String, password: String): AuthIdentity
    suspend fun signInWithGoogle(idToken: String): AuthIdentity
    suspend fun linkWithGoogle(idToken: String): AuthIdentity
    suspend fun sendVerificationEmail(): AuthIdentity
    suspend fun sendPasswordReset(email: String)
    suspend fun reloadAndRefreshToken(): AuthIdentity
    suspend fun reauthenticateWithEmail(email: String, password: String)
    suspend fun reauthenticateWithGoogle(idToken: String)
    suspend fun signOut()
}
