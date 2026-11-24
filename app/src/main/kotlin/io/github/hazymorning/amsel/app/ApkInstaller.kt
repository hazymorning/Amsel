package io.github.hazymorning.amsel.app

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PackageManager.CERT_INPUT_RAW_X509
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import java.io.File
import java.io.IOException

/** Hands a downloaded update to Android's package installer, if it is Amsel under its own name and key. */
class ApkInstaller(private val app: Context) {
    // Android updates the app only from the same package name and key; another name installs as a new app.
    fun isOurs(apk: File): Boolean {
        val pm = app.packageManager

        // Android 9 and 10 read an archive's certificates only for GET_SIGNATURES.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.path, PackageInfoFlags.of(flags.toLong()))
        } else {
            // The flags as an Int are deprecated from API 33, where PackageInfoFlags replaces them.
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, flags)
        } ?: return false
        val signing = info.signingInfo ?: return false
        val signers = with(signing) { if (hasMultipleSigners()) apkContentsSigners else signingCertificateHistory }
        return info.packageName == app.packageName &&
            signers.orEmpty().any { pm.hasSigningCertificate(app.packageName, it.toByteArray(), CERT_INPUT_RAW_X509) }
    }

    /**
     * Writes [apk] into an install session and commits it; Android answers [UpdateResultReceiver].
     * A session, not ACTION_VIEW on the file: that way Android 16 did not install the update.
     */
    fun install(apk: File, version: String) {
        val installer = app.packageManager.packageInstaller
        installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(UpdateResultReceiver.pending(app, id, version).intentSender)
            }
        } catch (e: IOException) {
            installer.abandonSession(id)
            throw e
        } catch (e: RuntimeException) {
            // SecurityException or IllegalStateException from a session Android closed or refused.
            installer.abandonSession(id)
            throw IOException("Android refused the install session", e)
        }
    }
}
