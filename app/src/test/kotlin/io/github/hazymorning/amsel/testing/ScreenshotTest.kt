package io.github.hazymorning.amsel.testing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

enum class ShotVariant(val mode: ThemeMode, val fontScale: Float) {
    Light(ThemeMode.Light, 1f),
    Dark(ThemeMode.Dark, 1f),
    LargeFont(ThemeMode.Light, 2f),
}

/**
 * Base class for screenshot tests; each test records one shot in the variants it names. Images
 * land in app/src/test/screenshots/<class name without "Shots">/<name>_<variant>.png, phone size
 * 411x891 dp, at half the phone's resolution.
 *
 * `-Plook=de` (or `de-w360dp`, `w360dp`) records every shot under those qualifiers into
 * app/build/look/<look>/ instead, at full resolution for scripts/design/measure.py: a way to look at
 * a screen in German or on a narrow phone without keeping the images.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
abstract class ScreenshotTest {
    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUpCovers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context)
                .components { add(SampleCovers()) }
                .coroutineContext(Dispatchers.Unconfined)
                .build(),
        )
    }

    @OptIn(DelicateCoilApi::class)
    @After
    fun tearDownLoader() {
        SingletonImageLoader.reset()
    }

    /**
     * Renders [content] full-screen in each of [variants], does [before] (a scroll), and records it.
     * [qualifiers] are Android's, for a shot kept in another language or width ("de", "w360dp").
     */
    fun snap(
        name: String,
        variants: List<ShotVariant> = listOf(ShotVariant.Light),
        qualifiers: String = "",
        before: SemanticsNodeInteractionsProvider.() -> Unit = {},
        content: @Composable () -> Unit,
    ) = record(name, variants, qualifiers, content) {
        before()
        waitForIdle()
        onRoot().captureRoboImage(it, roborazziOptions = options)
    }

    /** As [snap], but records every window, so a modal sheet is captured with its scrim over [content]. */
    @OptIn(ExperimentalRoborazziApi::class)
    fun snapScreen(
        name: String,
        variants: List<ShotVariant> = listOf(ShotVariant.Light),
        qualifiers: String = "",
        content: @Composable () -> Unit,
    ) = record(name, variants, qualifiers, content) { captureScreenRoboImage(it, roborazziOptions = options) }

    private fun record(
        name: String,
        variants: List<ShotVariant>,
        qualifiers: String,
        content: @Composable () -> Unit,
        capture: ComposeUiTest.(path: String) -> Unit,
    ) {
        val group = javaClass.simpleName.removeSuffix("Shots")
        val folder = if (look.isEmpty()) "src/test/screenshots" else "build/look/$look"
        for (variant in variants) {
            // Each variant gets its own activity, started after its configuration is set.
            RuntimeEnvironment.setQualifiers(if (variant.mode == ThemeMode.Dark) "+night" else "+notnight")
            // One at a time: Android wants a language before a width, and a look may hold both.
            for (qualifier in "$qualifiers-$look".split('-')) {
                if (qualifier.isNotEmpty()) RuntimeEnvironment.setQualifiers("+$qualifier")
            }
            RuntimeEnvironment.setFontScale(variant.fontScale)
            runComposeUiTest {
                setContent {
                    AmselTheme(mode = variant.mode) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
                    }
                }
                waitForIdle()
                capture("$folder/$group/${name}_${variant.name.lowercase()}.png")
            }
        }
    }

    private val look = System.getProperty(LOOK).orEmpty()

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = if (look.isEmpty()) GOLDEN_SCALE else 1.0),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f),
    )
}

/** Goldens are compared at half size: a layout change still shows, at a quarter of the bytes. */
private const val GOLDEN_SCALE = 0.5

private const val LOOK = "amsel.look"
