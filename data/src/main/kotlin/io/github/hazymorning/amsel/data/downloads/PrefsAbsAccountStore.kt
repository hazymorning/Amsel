package io.github.hazymorning.amsel.data.downloads

import io.github.hazymorning.amsel.data.AppPrefs
import io.github.hazymorning.amsel.data.TokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Audiobookshelf account in [prefs], its token sealed by [vault] as the session's is. */
class PrefsAbsAccountStore(private val prefs: AppPrefs, private val vault: TokenVault, scope: CoroutineScope) :
    AbsAccountStore {
    override val account: StateFlow<AbsAccount?> = prefs.prefs.map { p ->
        val url = p?.absUrl ?: return@map null
        AbsAccount(url, p.absUser.orEmpty())
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun refreshToken(): String? = prefs.absToken()?.let(vault::open)

    // Each returns once [account] shows the change: a download asked for right after needs it.
    override suspend fun save(account: AbsAccount, refreshToken: String) {
        prefs.saveAbs(account.url, account.username, vault.seal(refreshToken))
        this.account.first { it == account }
    }

    override suspend fun saveRefreshToken(token: String) = prefs.saveAbsToken(vault.seal(token))

    override suspend fun clear() {
        prefs.clearAbs()
        account.first { it == null }
    }
}
