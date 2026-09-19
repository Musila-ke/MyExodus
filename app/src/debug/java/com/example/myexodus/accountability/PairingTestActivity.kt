package com.example.myexodus.accountability

import android.annotation.SuppressLint
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
 * and signs the transcript with its hardware-bound biometric key; both devices display the
 * SAS for the out-of-band comparison before the initiator signs. Covers §7 step 8 and §10
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
            text = "I am the INITIATOR (shows QR)"
            setOnClickListener { log("role = initiator") }
        })
        addView(Button(this@PairingTestActivity).apply {
            text = "I am the PARTNER (pastes QR)"
            setOnClickListener { log("role = partner") }
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
            hint = "PARTNER: paste base64 QR here"
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
                val bytes = runCatching { Base64.decode(qrInput.text.toString().trim(), Base64.NO_WRAP) }.getOrNull()
                if (bytes == null) {
                    log("QR base64 decode failed")
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
            .setNegativeButtonText("Cancel") // required when BIOMETRIC_STRONG-only (crash caught on the X6532)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        if (cryptoReady) {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
        } else {
            prompt.authenticate(promptInfo)
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
    }
}
