package io.github.hazymorning.amsel.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeLayoutStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "home.preferences_pb")

    /** A DataStore on [file] for the length of [block], closed afterwards like the app's on exit. */
    private fun <T> withStore(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(PreferenceDataStoreFactory.create(scope = scope) { file() })
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        }
    }

    @Test fun nothingStoredGivesTheDefaults() = withStore { store ->
        assertEquals(HomeLayout(), HomeLayoutStore(store).layout("server-a"))
    }

    @Test fun editsAreKeptAcrossLaunches() {
        val edited = HomeLayout().setLayout("s3", SectionLayout.List).pin("library://album/1")
        withStore { store -> HomeLayoutStore(store).save("server-a", edited) }
        val read = withStore { store -> HomeLayoutStore(store).layout("server-a") }
        assertEquals(edited, read)
        assertEquals(SectionLayout.List, read.sections.single { it.id == "s3" }.layout)
        assertEquals(listOf("library://album/1"), read.pinned)
    }

    @Test fun eachServerHasItsOwnHome() = withStore { store ->
        val layouts = HomeLayoutStore(store)
        layouts.save("server-a", HomeLayout().remove("s4"))
        layouts.save("server-b", HomeLayout().pin("library://album/9"))
        assertEquals(4, layouts.layout("server-a").sections.size)
        assertEquals(emptyList<String>(), layouts.layout("server-a").pinned)
        assertEquals(5, layouts.layout("server-b").sections.size)
        assertEquals(listOf("library://album/9"), layouts.layout("server-b").pinned)
    }

    @Test fun anUnreadableStoredLayoutFallsBackToTheDefaults() = withStore { store ->
        store.edit { it[stringPreferencesKey("layout_server-a")] = "{broken" }
        assertEquals(HomeLayout(), HomeLayoutStore(store).layout("server-a"))
    }
}
