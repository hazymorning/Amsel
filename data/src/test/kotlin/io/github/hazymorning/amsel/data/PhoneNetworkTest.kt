package io.github.hazymorning.amsel.data

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class PhoneNetworkTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(ConnectivityManager::class.java)

    private fun on(vararg transports: Int) {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(capabilities).addTransportType(it) }
        shadowOf(manager).setNetworkCapabilities(requireNotNull(manager.activeNetwork), capabilities)
    }

    @Test fun `plain http stays on the phone on mobile data, unless a VPN carries it`() {
        val network = AndroidPhoneNetwork(app)
        on(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(network.plainHttpAllowed())
        on(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN)
        assertTrue(network.plainHttpAllowed())
        on(NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(network.plainHttpAllowed())
    }

    @Test fun `a network is kept as text and read back`() {
        val key = NetworkKey(boot = 12, handles = listOf(4294967309L, 8589934605L))
        assertEquals(key, NetworkKey.parse(key.text))
        assertNull(NetworkKey.parse("12:wifi"))
        assertNull(NetworkKey.parse(""))
    }
}
