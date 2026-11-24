package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.sample.HomeSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every skeleton block is as tall as the content that replaces it, so nothing jumps on load. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SkeletonGeometryTest(private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font {0}")
        fun scales() = listOf(arrayOf<Any>(1f), arrayOf<Any>(2f))
    }

    @get:Rule val compose = createComposeRule()

    @Before fun setFontScale() = RuntimeEnvironment.setFontScale(fontScale)

    // One-line titles, as most are: a two-line title makes its card row taller than the skeleton's.
    private fun section(
        layout: SectionLayout,
        count: Int,
        option: SectionOption = SectionOption.Everything,
        items: List<MediaUi> = SampleData.jumpBackIn,
    ) = SectionUi("s", option, layout, items.take(count))

    private fun assertSameHeight(section: SectionUi) = assertSameHeight(
        content = { SectionContent(section, onOpen = {}, onMore = {}, modifier = it) },
        skeleton = { SectionSkeleton(section.copy(items = emptyList(), loading = true), it) },
    )

    @Test fun grid() = assertSameHeight(section(SectionLayout.Grid, 4))

    @Test fun small() = assertSameHeight(section(SectionLayout.Small, 6))

    @Test fun row() = assertSameHeight(section(SectionLayout.Row, 6))

    @Test fun big() = assertSameHeight(section(SectionLayout.Big, 6))

    @Test fun list() = assertSameHeight(section(SectionLayout.List, 3))

    @Test fun rowOfStations() =
        assertSameHeight(section(SectionLayout.Row, 4, SectionOption.FavoriteStations, HomeSample.stations))

    @Test fun bigArtists() =
        assertSameHeight(section(SectionLayout.Big, 4, SectionOption.FavoriteArtists, SampleData.artists))

    private fun assertSameHeight(content: @Composable (Modifier) -> Unit, skeleton: @Composable (Modifier) -> Unit) {
        compose.setContent {
            AmselTheme(mode = ThemeMode.Light) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // A box per block, so the bounds include the space a row keeps under its band.
                    Box(Modifier.testTag("content")) { content(Modifier) }
                    Box(Modifier.testTag("skeleton")) { skeleton(Modifier) }
                }
            }
        }
        // Rows round their text to whole pixels; over a few rows at 200% that adds up to about a dp.
        assertEquals(height("content").value, height("skeleton").value, 2f)
    }

    private fun height(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let {
        it.bottom - it.top
    }
}
