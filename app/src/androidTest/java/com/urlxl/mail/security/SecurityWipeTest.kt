package com.urlxl.mail.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.urlxl.mail.push.PairingData
import com.urlxl.mail.push.SecurePairingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityWipeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun wipeAndResetApp_clearsPinPairingAndLockState() = runBlocking {
        val appLockStore = AppLockStore(context)
        appLockStore.setPin("123456")
        appLockStore.setLockEnabled(true)

        SecurePairingStore(context).savePairing(
            PairingData(
                subscriberId = "sub", serverUrl = "https://example.com",
                registrationUrl = "https://example.com/register", pairingToken = "token",
                deviceId = "device", deviceSecret = "secret", pairedAtEpochMs = 1L,
            ),
        )

        SecurityWipe.wipeAndResetApp(context)

        assertFalse(AppLockStore(context).isLockEnabled())
        assertFalse(AppLockStore(context).verifyPin("123456"))
        assertNull(SecurePairingStore(context).pairing.value)
    }
}
