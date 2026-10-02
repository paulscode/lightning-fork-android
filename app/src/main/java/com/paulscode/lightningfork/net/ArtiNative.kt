package com.paulscode.lightningfork.net

/**
 * JNI binding to the embedded Arti (Tor) client in rust/lf-arti, which serves a
 * local SOCKS5 proxy the app sends `.onion` requests through. The symbols are
 * `Java_com_paulscode_lightningfork_net_ArtiNative_*`.
 */
object ArtiNative {
    @Volatile private var loaded = false

    /** @return true if the native library is present and loaded. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("lf_arti")
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Begin bootstrapping, serving SOCKS on a free port of 127.0.0.1. Returns the
     * port (the same one while a client runs), or -1. Returns immediately.
     */
    external fun nativeStart(stateDir: String, cacheDir: String): Int

    /** Bootstrap progress 0..100, or -1 on failure. */
    external fun nativeBootstrapPercent(): Int
}
