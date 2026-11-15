package io.github.hazymorning.amsel.data

import kotlinx.coroutines.flow.MutableStateFlow

/** The phone's networks as a test sets them: at [HOME] until [current] moves it. */
internal class FakePhoneNetwork(at: NetworkKey? = HOME) : PhoneNetwork {
    override val current = MutableStateFlow(at)
    var plainHttp = true

    override fun plainHttpAllowed() = plainHttp

    companion object {
        val HOME = NetworkKey(boot = 7, handles = listOf(100L))
        val CAFE = NetworkKey(boot = 7, handles = listOf(101L))
    }
}
