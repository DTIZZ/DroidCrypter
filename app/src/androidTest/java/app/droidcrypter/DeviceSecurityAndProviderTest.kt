package app.droidcrypter

import android.text.SpannableStringBuilder
import app.droidcrypter.security.NativeWipe
import app.droidcrypter.ui.SecureInputController
import java.lang.AssertionError

/**
 * On-device security & native wipe verification (section 8.4).
 */
class DeviceSecurityAndProviderTest {

    fun verifyNativeWipeZeroesArray() {
        check(NativeWipe.load()) { "libsecurewipe failed to load on device" }
        val secret = ByteArray(256) { (it + 1).toByte() }
        NativeWipe.wipe(secret)
        if (!secret.all { it == 0.toByte() }) {
            throw AssertionError("NativeWipe.wipe did not zero all bytes")
        }
    }

    fun verifyEditableWipeInPlace() {
        val ssb = SpannableStringBuilder("SuperSecretPassword123!")
        SecureInputController.wipeEditableInPlace(ssb)
        if (ssb.isNotEmpty()) {
            throw AssertionError("Editable was not cleared after wipeEditableInPlace")
        }
    }
}

