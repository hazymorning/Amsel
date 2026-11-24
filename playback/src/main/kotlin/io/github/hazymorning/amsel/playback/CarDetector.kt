package io.github.hazymorning.amsel.playback

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Auto on the phone, or a car's own Android Automotive. Media3 knows them by package name
 * alone, which any app can take where they are not installed, and which a controller names itself
 * before Android 13. So a car counts when Android trusts it, or when its package is a system app
 * installed under the controller's uid. Android Auto from Play on Android 9 is no system app: it
 * counts only while Android trusts it, as it does an app with notification access.
 */
@OptIn(UnstableApi::class)
class CarDetector(private val packages: PackageManager) {
    /** By uid: an app stays a system app, or not, while it is installed. */
    private val checked = ConcurrentHashMap<Int, Boolean>()

    fun isCar(controller: MediaSession.ControllerInfo): Boolean {
        val name = controller.packageName
        if (name !in NAMES) return false
        if (controller.isTrusted) return true
        return checked.getOrPut(controller.uid) { isSystemApp(name, controller.uid) }
    }

    private fun isSystemApp(name: String, uid: Int): Boolean {
        val app = try {
            packages.getApplicationInfo(name, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return app.uid == uid && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private companion object {
        /** Those of MediaSession.isAutoCompanionController and isAutomotiveController. */
        val NAMES = setOf(
            "com.google.android.projection.gearhead",
            "com.android.car.media",
            "com.android.car.carlauncher",
        )
    }
}
