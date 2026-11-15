# Bouncy Castle lightweight API used for Serpent, Twofish, Whirlpool, and Streebog-512.
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**

# JNI entry points for native C++20 secure memory wipe (libsecurewipe.so).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class app.droidcrypter.security.NativeWipe {
    public static native void wipe(byte[]);
}

# Strip debug/verbose logs in release builds (section 4.5).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
