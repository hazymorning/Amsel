package io.github.hazymorning.amsel.playback

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Android Auto, or a car's own media app, browses and controls as a car once it is found to be the
 * real one; an app that only took its package name gets what any other app gets.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class CarDetectorTest {
    private val app = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The apps the covers were opened to. */
    private val covers = mutableListOf<String>()
    private val context = object : ContextWrapper(app) {
        override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
            covers += toPackage
        }
    }
    private var carPlays = 0
    private val speaker = FakeSpeaker(SpeakerSnapshot("Kitchen", available = true, playing = false), emptyList()) {
        0L
    }
    private val player = RemotePlayer(
        "kitchen",
        speaker.snapshots,
        speaker,
        { 0L },
        local = null,
        onCommandFailed = {},
        appScope = scope,
    )
    private val tree = BrowseTree(
        Catalogless,
        scope,
        app::getString,
        "Amsel",
        CarArtwork(context),
        CarDetector(app.packageManager),
        beforeCarPlay = {
            carPlays++
            true
        },
        blocked = { null },
        voiceUri = { _, _ -> null },
        openApp = null,
    )
    private val session = MediaLibrarySession.Builder(app, player, tree).build()

    @After fun tearDown() {
        session.release()
        player.release()
        scope.cancel()
    }

    private fun connect(packageName: String, uid: Int, trusted: Boolean = false): ConnectionResult =
        tree.onConnectAsync(session, controllerOf(packageName, uid, trusted)).get()

    private val ConnectionResult.browses: Boolean
        get() = availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)

    @Test fun anAppThatOnlyTookACarsNameNeitherBrowsesNorControls() {
        CARS.forEachIndexed { i, name ->
            installApp(name, OTHER_UID + i, system = false)
            val result = connect(name, OTHER_UID + i)
            assertFalse(name, result.browses)
            assertEquals(name, ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS, result.availablePlayerCommands)
        }
        assertEquals(emptyList<String>(), covers)
    }

    @Test fun theCarsOwnAppsAsTheFirmwareBringsThemBrowseControlAndReadTheCovers() {
        CARS.forEachIndexed { i, name ->
            installApp(name, AUTO_UID + i, system = true)
            val result = connect(name, AUTO_UID + i)
            assertTrue(name, result.browses)
            assertEquals(name, ConnectionResult.DEFAULT_PLAYER_COMMANDS, result.availablePlayerCommands)
        }
        assertEquals(CARS, covers)
    }

    @Test fun aCarsNameFromAnotherUidCountsForNothing() {
        // Before Android 13 a controller names its package itself.
        installApp(ANDROID_AUTO, AUTO_UID, system = true)
        assertFalse(connect(ANDROID_AUTO, OTHER_UID).browses)
        assertEquals(emptyList<String>(), covers)
    }

    @Test fun androidAutoThatAndroidTrustsBrowsesThoughNoSystemApp() {
        // Android Auto from Play on Android 9, with notification access.
        installApp(ANDROID_AUTO, AUTO_UID, system = false)
        assertTrue(connect(ANDROID_AUTO, AUTO_UID, trusted = true).browses)
        assertEquals(listOf(ANDROID_AUTO), covers)
    }

    @Test fun aPlayFromAnAppUnderAndroidAutosNameStaysWithTheSelectedSpeaker() {
        installApp(ANDROID_AUTO, OTHER_UID, system = false)
        val items = mutableListOf(MediaItem.Builder().setMediaId("library://album/7").build())
        val result = tree.onSetMediaItems(session, controllerOf(ANDROID_AUTO, OTHER_UID), items, 0, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(items, result.get().mediaItems)
        assertEquals(0, carPlays)
    }

    private companion object {
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        val CARS = listOf(ANDROID_AUTO, "com.android.car.media", "com.android.car.carlauncher")
        const val AUTO_UID = 10_150
        const val OTHER_UID = 10_300
    }
}

/** [packageName] installed under [uid]: a system app, as a phone's or a car's firmware brings it, or not. */
internal fun installApp(packageName: String, uid: Int, system: Boolean) {
    val app = ApplicationInfo().apply {
        this.packageName = packageName
        this.uid = uid
        if (system) flags = ApplicationInfo.FLAG_SYSTEM
    }
    val info = PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = app
    }
    shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(info)
}

/** [packageName]'s controller under [uid], as Media3 hands it to the session. */
@OptIn(UnstableApi::class)
internal fun controllerOf(packageName: String, uid: Int, trusted: Boolean = false): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, 0, uid, 0, 0, trusted, Bundle.EMPTY, true)
