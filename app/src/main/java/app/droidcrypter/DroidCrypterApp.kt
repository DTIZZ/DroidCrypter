package app.droidcrypter

import android.app.Application
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import app.droidcrypter.engine.Secrets
import app.droidcrypter.security.NativeWipe

@UnstableApi
class DroidCrypterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) {
            Log.setLogLevel(Log.LOG_LEVEL_OFF)
        }
        if (NativeWipe.load()) {
            Secrets.installWiper(NativeWipe::wipe)
        }
    }
}
