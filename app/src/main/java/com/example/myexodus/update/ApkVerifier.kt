package com.example.myexodus.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

internal class ApkVerifier(private val context: Context) {
    fun verify(file: File, manifest: UpdateManifest, installedVersionCode: Long) {
        if (manifest.versionCode <= installedVersionCode) throw UpdateException(UpdateFailure.DOWNGRADE)
        if (file.length() != manifest.apkSizeBytes) throw UpdateException(UpdateFailure.INTEGRITY)
        if (sha256Hex(file) != manifest.apkSha256) throw UpdateException(UpdateFailure.INTEGRITY)
        val archive = archiveInfo(file) ?: throw UpdateException(UpdateFailure.INVALID_APK)
        if (archive.packageName != context.packageName) throw UpdateException(UpdateFailure.WRONG_PACKAGE)
        if (versionCode(archive) != manifest.versionCode) {
            throw UpdateException(UpdateFailure.VERSION_MISMATCH)
        }
        val archiveSigners = signerDigests(archive)
        val installedSigners = installedSignerDigests()
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() ||
            archiveSigners != installedSigners
        ) {
            throw UpdateException(UpdateFailure.SIGNER_MISMATCH)
        }
    }

    private fun archiveInfo(file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())

    private fun installedSignerDigests(): Set<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
        return signerDigests(info)
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.mapTo(LinkedHashSet()) { sha256Hex(it.toByteArray()) }
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
}
