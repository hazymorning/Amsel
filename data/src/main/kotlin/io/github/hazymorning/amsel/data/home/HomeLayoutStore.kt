package io.github.hazymorning.amsel.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

// Keyed by server, so another server's Home never mixes in, and signing back in finds the old one.
class HomeLayoutStore(private val store: DataStore<Preferences>) {
    suspend fun layout(serverId: String): HomeLayout = HomeLayout.decode(store.data.first()[key(serverId)])

    suspend fun save(serverId: String, layout: HomeLayout) {
        store.edit { it[key(serverId)] = layout.encode() }
    }

    private fun key(serverId: String) = stringPreferencesKey("layout_$serverId")
}
