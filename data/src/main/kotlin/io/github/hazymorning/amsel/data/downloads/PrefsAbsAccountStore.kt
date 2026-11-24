package io.github.hazymorning.amsel.data.downloads

import io.github.hazymorning.amsel.data.PrefsStore
import io.github.hazymorning.amsel.data.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PrefsAbsAccountStore(private val prefs: PrefsStore, private val vault: TokenCipher, scope: CoroutineScope) :
    AbsAccountStore {
    override val account: StateFlow<AbsAccount?> = prefs.prefs.map { p ->
        val url = p?.absUrl ?: return@map null
        AbsAccount(url, p.absUser.orEmpty())
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun refreshToken(): String? = prefs.absToken()?.let(vault::open)

    // save and clear return once [account] shows the change: a download asked for right after needs it.
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
