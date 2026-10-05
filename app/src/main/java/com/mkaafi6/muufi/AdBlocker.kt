package com.mkaafi6.muufi

/**
 * JNI bridge to the Rust ad-blocking engine (adblock-rust).
 *
 * The native library is built in CI (cargo-ndk) and packaged at
 * jniLibs/arm64-v8a/libmuufi_adblock.so.
 */
object AdBlocker {

    init {
        System.loadLibrary("muufi_adblock")
    }

    /** Builds the engine from every *.txt file in [filterDir]. Returns true on success. */
    external fun nativeInit(filterDir: String): Boolean

    /** True if the request should be blocked. */
    external fun nativeShouldBlock(
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): Boolean

    /**
     * Returns a JSON string: { "hide":[...selectors], "script":"...", "generichide":bool }
     * for the given page URL.
     */
    external fun nativeCosmetics(url: String): String

    @Volatile
    private var ready = false

    fun isReady(): Boolean = ready

    fun init(filterDir: String) {
        ready = nativeInit(filterDir)
    }
}
