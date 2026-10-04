package com.example.myexodus.accountability

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.PairingCode
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import com.google.firebase.auth.FirebaseAuth
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.Signature
import kotlinx.coroutines.launch

/**
 * §10 pairing on-device harness (no instrumentation — same XOS constraint as
 * DeviceVerificationActivity). Two devices run this activity, one per role: the initiator
 * shows a QR of the single-use token + enrollment payload; the partner pastes the base64 QR
 * (or types the §19.4 item 1 invitation code — the same token, Crockford rendering) and
 * signs the transcript with its hardware-bound signing key (biometric prompt, ADR #112
 * device-credential fallback); both devices display the SAS
 * for the out-of-band comparison before the initiator signs. Covers §7 step 8 and §10
 * steps 1-5, plus the §10 step 4 revocation seam (Delete pair button).
 *
 * Usage: am start -n <app>/.accountability.PairingTestActivity
 */
@SuppressLint("SetTextI18n", "UseKtx") // dev-only harness: plain English labels, no resources
class PairingTestActivity : FragmentActivity() {

    private val entryPoint: PairingTestEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, PairingTestEntryPoint::class.java)
    }
    // lazy: TextView(this) in a property initializer runs in <init>, before the activity
    // context is attached — getResources() NPEs (crash caught on the X6532, 2026-08-30).
    private val logView: TextView by lazy { TextView(this).apply { textSize = 14f } }

    private var uid: String? = null
    private var pendingAwait: PairingFlow.InitiatorAwait? = null
    private var pendingToken: PairingToken? = null
    private var pendingSignature: Signature? = null
    private var screenLockCallback: ((Signature) -> Unit)? = null
    private lateinit var confirmButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui())
        signInAnonymously()
    }

    private fun ui(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 24)

        addView(Button(this@PairingTestActivity).apply {
            text = "Sign in anonymously"
            setOnClickListener { signInAnonymously() }
        })
        addView(Button(this@PairingTestActivity).apply {
            text = "Warm signing window (fingerprint, no-op)"
            setOnClickListener {
                signWithBiometric("Warm signing window") {
                    log("signing window warm — run the sync suite within 900s")
                }
            }
        })
        addView(Button(this@PairingTestActivity).apply {
            text = "Sign via screen lock (ADR #112 fallback)"
            setOnClickListener { signWithScreenLock() }
        })
        val qrImage = ImageView(this@PairingTestActivity).apply {
            adjustViewBounds = true
            // 240px: the 720x1600 X6532 clips the Delete-pair row even at 360 (caught 2026-09-16)
            maxHeight = 240
        }
        addView(qrImage)

        addView(Button(this@PairingTestActivity).apply {
            text = "INITIATOR: generate token + show QR"
            setOnClickListener { initiate(qrImage) }
        })
        confirmButton = Button(this@PairingTestActivity).apply {
            text = "INITIATOR: SAS matched — biometric-sign & confirm"
            isEnabled = false
            setOnClickListener { confirmAsInitiator() }
        }
        addView(confirmButton)

        val qrInput = EditText(this@PairingTestActivity).apply {
            hint = "PARTNER: paste base64 QR, or type the invitation code"
            // The 124-char base64 wraps to 4+ lines and pushes every row below off-screen
            // on a 720x1600 device (caught 2026-09-16); 2 lines cap the growth.
            maxLines = 2
        }
        addView(qrInput)
        addView(Button(this@PairingTestActivity).apply {
            text = "PARTNER: load last QR from cache"
            setOnClickListener {
                val text = runCatching { File(cacheDir, QR_CACHE_FILE).readText() }.getOrNull()
                if (text == null) log("no cached QR — run INITIATOR first") else {
                    qrInput.setText(text)
                    log("cached QR loaded (${text.length} chars)")
                }
            }
        })
        addView(Button(this@PairingTestActivity).apply {
            text = "PARTNER: biometric-sign & redeem"
            setOnClickListener {
                val text = qrInput.text.toString().trim()
                // Typed invitation code (§19.4 item 1): parse is exact-length (13 chars
                // normalized), so a base64 QR payload can never be mistaken for one.
                if (PairingCode.parse(text) is PairingCode.ParseResult.Valid) {
                    signWithBiometric("Sign transcript as partner") { signature ->
                        completeAsPartnerCode(text, signature)
                    }
                    return@setOnClickListener
                }
                val bytes = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()
                if (bytes == null) {
                    log("input is neither a typed invitation code nor QR base64")
                    return@setOnClickListener
                }
                signWithBiometric("Sign transcript as partner") { signature ->
                    completeAsPartner(bytes, signature)
                }
            }
        })

        val pairIdInput = EditText(this@PairingTestActivity).apply {
            hint = "pair id (for Delete pair)"
        }
        val partnerUidInput = EditText(this@PairingTestActivity).apply {
            hint = "partner uid (for Delete pair)"
        }
        addView(pairIdInput)
        addView(partnerUidInput)
        addView(Button(this@PairingTestActivity).apply {
            text = "Delete pair (§10 step 4 revocation)"
            setOnClickListener {
                val pairId = pairIdInput.text.toString().trim()
                val partnerUid = partnerUidInput.text.toString().trim()
                if (pairId.isEmpty() || partnerUid.isEmpty()) {
                    log("pair id and partner uid required")
                    return@setOnClickListener
                }
                deletePair(pairId, partnerUid)
            }
        })

        val logScroll = ScrollView(this@PairingTestActivity).apply {
            addView(logView.apply {
                gravity = Gravity.START
                setPadding(0, 12, 0, 0)
            })
        }
        addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply {
            weight = 1f
        })
    }

    private fun signInAnonymously() {
        // Fresh identity per tap: signInAnonymously alone reuses the current anonymous user,
        // but the two pairing roles must hold DISTINCT uids (a real second device's shape) —
        // otherwise the pair doc's members has one entry and PairUidResolver finds no partner.
        FirebaseAuth.getInstance().signOut()
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener { result ->
                uid = result.user?.uid
                log("anonymous sign-in ok: uid=$uid")
                val keys = entryPoint.keystore().ensureKeys()
                log("hardware keys: $keys")
            }
            .addOnFailureListener { e ->
                log("anonymous sign-in FAILED: ${e.message} — enable the Anonymous provider in the Firebase console")
            }
    }

    private fun initiate(qrImage: ImageView) {
        val myUid = requireUid() ?: return
        lifecycleScope.launch {
            log("generating single-use token (10 min TTL)...")
            val initiation = runCatching { entryPoint.pairingFlow().initiate(myUid) }
            if (initiation.isFailure) {
                log("initiate failed: ${initiation.exceptionOrNull()?.message}")
                return@launch
            }
            val qr = initiation.getOrThrow()
            val text = Base64.encodeToString(qr.qrBytes, Base64.NO_WRAP)
            log("QR ready — session ${qr.token.sessionId} (also logged as QR_TEXT for capture)")
            Log.i(TAG, "QR_TEXT: $text")
            // §19.4 item 1: the same token as a 19-char typed code — short enough to type
            // into the partner's input field directly (single-device runs) or read aloud.
            val code = PairingCode.encode(qr.token)
            log("typed invitation code: $code")
            Log.i(TAG, "CODE_TEXT: $code")
            // Single-device run: the base64 is too long for adb input text, so cache it for
            // the "load last QR" button instead of shell-injecting it.
            runCatching { File(cacheDir, QR_CACHE_FILE).writeText(text) }
            qrImage.setImageBitmap(renderQr(text))
            log("waiting for the partner's response (up to 10 min)...")
            val await = runCatching { entryPoint.pairingFlow().awaitPartner(qr.token, PairingToken.TTL_MILLIS) }
            if (await.isFailure) {
                log("await failed: ${await.exceptionOrNull()?.message}")
                return@launch
            }
            if (await.getOrNull() == null) {
                log("partner material rejected or timed out — pair aborted")
                return@launch
            }
            pendingAwait = await.getOrNull()
            pendingToken = qr.token
            log("PARTNER VERIFIED. SAS (compare on both devices): ${await.getOrNull()?.sas}")
            log("if both devices show the same SAS, tap 'SAS matched — sign & confirm'")
            confirmButton.isEnabled = true
        }
    }

    private fun confirmAsInitiator() {
        val await = pendingAwait ?: run { log("no verified partner material yet"); return }
        val token = pendingToken ?: run { log("no pending token — re-initiate"); return }
        val myUid = requireUid() ?: return
        signWithBiometric("Sign pairing proof as initiator") { signature ->
            lifecycleScope.launch {
                val outcome = runCatching {
                    entryPoint.pairingFlow().submitInitiatorSignature(await, token, myUid, signature)
                }
                log("initiator outcome: ${outcome.getOrNull() ?: outcome.exceptionOrNull()}")
            }
        }
    }

    private fun completeAsPartner(qrBytes: ByteArray, signature: Signature) {
        val myUid = requireUid() ?: return
        lifecycleScope.launch {
            log("verifying initiator identity, signing transcript + proof, responding on token...")
            val outcome = runCatching { entryPoint.pairingFlow().complete(qrBytes, myUid, signature) }
            log("partner outcome: ${outcome.getOrNull() ?: outcome.exceptionOrNull()}")
            (outcome.getOrNull() as? PairingFlow.Outcome.Paired)?.let {
                log("SAS (compare with initiator): ${it.sas}")
            }
        }
    }

    private fun completeAsPartnerCode(code: String, signature: Signature) {
        val myUid = requireUid() ?: return
        lifecycleScope.launch {
            log("typed code passed the local check — looking up the token document...")
            val outcome = runCatching { entryPoint.pairingFlow().completeWithCode(code, myUid, signature) }
            log("partner outcome (typed code): ${outcome.getOrNull() ?: outcome.exceptionOrNull()}")
            (outcome.getOrNull() as? PairingFlow.Outcome.Paired)?.let {
                log("SAS (compare with initiator): ${it.sas}")
            }
        }
    }

    private fun deletePair(pairId: String, partnerUid: String) {
        val myUid = requireUid() ?: return
        signWithBiometric("Sign deletion request") { signature ->
            lifecycleScope.launch {
                val result = runCatching {
                    entryPoint.deletion().initiate(pairId, myUid, partnerUid, signature)
                }
                log("deletion result: ${result.getOrNull() ?: result.exceptionOrNull()}")
            }
        }
    }

    private fun requireUid(): String? {
        val current = uid
        if (current == null) log("not signed in yet — tap 'Sign in anonymously'")
        return current
    }

    /**
     * BiometricPrompt signing; the key itself gates signing. Two paths (caught on the X6532,
     * 2026-08-30): when initSign succeeds, authenticate with the Signature as a CryptoObject
     * (canonical flow). When keystore2 rejects initSign without a fresh auth token AND the
     * crypto object would crash on the uninitialized primitive, prompt first WITHOUT crypto
     * and initSign inside the success callback — the 900s timeout window (§NFR4) makes the
     * deposited token valid for the signature.
     */
    private fun signWithBiometric(title: String, onSigned: (Signature) -> Unit) {
        val signature = Signature.getInstance("SHA256withECDSA")
        val cryptoReady = try {
            signature.initSign(entryPoint.keystore().signingPrivateKey())
            true
        } catch (e: Exception) {
            log("initSign needs fresh auth — prompting without crypto first")
            false
        }
        pendingSignature = signature
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val signed = if (cryptoReady) {
                        result.cryptoObject?.signature ?: return
                    } else {
                        try {
                            signature.initSign(entryPoint.keystore().signingPrivateKey())
                        } catch (e: Exception) {
                            log("initSign retry failed: ${e.message}")
                            return
                        }
                        signature
                    }
                    log("biometric ok")
                    onSigned(signed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    log("biometric error: $errString")
                }

                override fun onAuthenticationFailed() {
                    log("biometric not recognized")
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Hardware-bound signing key")
            // ADR #112 / owner ruling #17: the sheet carries the screen-lock affordance inline
            // (DEVICE_CREDENTIAL joins BIOMETRIC_STRONG); a negative button is forbidden with
            // it. Mirrors MainActivity's combined signing sheet so a device without usable
            // biometrics can still warm/sign here via its screen lock.
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        if (cryptoReady) {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
        } else {
            prompt.authenticate(promptInfo)
        }
    }

    /**
     * ADR #112 device leg: the platform's confirm-credential flow authorizes the key, then
     * the signature must verify against the device's own public key. A pre-ruling
     * biometric-only key (immutable) refuses with UserNotAuthenticatedException — logged as
     * SCREEN-LOCK REJECTED, the honest answer for that device.
     */
    private fun signWithScreenLock() {
        val confirm = getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent("Sign via screen lock", "ADR #112 fallback leg")
        if (confirm == null) {
            log("no secure lock screen on this device — nothing to confirm with")
            return
        }
        screenLockCallback = { signature ->
            val data = "screen-lock-fallback-probe".toByteArray()
            // finishSign (not raw sign()): §10 low-S normalization — the verifier below
            // rejects high-S, and raw keystore output is high-S ~50% of the time.
            val der = runCatching { signature.update(data); AccountabilitySigner().finishSign(signature) }
                .onFailure { log("sign failed: ${it.message}") }
                .getOrNull()
            if (der != null) {
                val verified = AccountabilitySigner()
                    .verify(entryPoint.keystore().signingPublicKey(), data, der)
                log(
                    if (verified) "SCREEN-LOCK SIGN OK — verified against the device public key"
                    else "SCREEN-LOCK SIGN INVALID — signature did not verify",
                )
            }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(confirm, REQ_SCREEN_LOCK)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCREEN_LOCK) return
        val callback = screenLockCallback ?: return
        screenLockCallback = null
        if (resultCode != RESULT_OK) {
            log("screen lock cancelled")
            return
        }
        val signature = Signature.getInstance(AccountabilitySigner.SIGNATURE_ALGORITHM)
        runCatching { signature.initSign(entryPoint.keystore().signingPrivateKey()) }
            .onSuccess {
                log("screen-lock confirm accepted by the key")
                callback(signature)
            }
            .onFailure { e ->
                log(
                    if (AccountabilitySigner().isUserNotAuthenticated(e)) {
                        "SCREEN-LOCK REJECTED — key refused the signature " +
                            "(pre-ADR #112 biometric-only key; immutable — enroll a biometric)"
                    } else {
                        "SCREEN-LOCK SIGN FAILED — ${e.message}"
                    },
                )
            }
    }

    private fun renderQr(text: String): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        logView.append("$message\n")
    }

    private companion object {
        const val TAG = "MyExodusPairing"
        const val QR_CACHE_FILE = "pairing_qr.txt"

        /** Arbitrary, outside androidx.biometric's own request-code range. */
        const val REQ_SCREEN_LOCK = 4711
    }
}
