package com.example.myexodus.update

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun MessageDigest.hex(): String = digest().toHex()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun decodePublicKey(base64: String): PublicKey =
    KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))
