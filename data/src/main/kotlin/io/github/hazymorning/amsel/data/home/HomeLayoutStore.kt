package io.github.hazymorning.amsel.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Each server's Home layout as a small JSON document in DataStore, so signing in to another
 * server never mixes two Homes, and signing back in finds the old one.
 */
class HomeLayoutStore(private val store: DataStore<Preferences>) {
    /** The layout stored for [serverId]; the defaults when there is none or it cannot be read. */
    suspend fun layout(serverId: String): HomeLayout = HomeLayout.decode(store.data.first()[key(serverId)])

    suspend fun save(serverId: String, layout: HomeLayout) {
        store.edit { it[key(serverId)] = layout.encode() }
    }

    private fun key(serverId: String) = stringPreferencesKey("layout_$serverId")
}
