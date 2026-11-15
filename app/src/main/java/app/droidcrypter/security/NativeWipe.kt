package app.droidcrypter.security

/** JNI bridge to libsecurewipe (see secure_wipe.cpp). */
object NativeWipe {
    @Volatile
    var loaded: Boolean = false
        private set

    fun load(): Boolean {
        if (!loaded) loaded = try {
            System.loadLibrary("securewipe"); true
        } catch (_: Throwable) {
            false
        }
        return loaded
    }

    fun isHardwareBacked(): Boolean = load()

    @JvmStatic
    external fun wipe(array: ByteArray)
}

