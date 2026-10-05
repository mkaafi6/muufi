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

    /**
     * Network check. Returns:
     *   "B" -> block, "R" + body -> redirect resource, "N" -> allow.
     */
    external fun nativeCheck(
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): String

    /**
     * Returns a JSON string: { "hide":[...selectors], "script":"...", "generichide":bool }
     * for the given page URL.
     */
    external fun nativeCosmetics(url: String): String

    /** Returns a JSON array of extra selectors for newly-seen classes/ids. */
    external fun nativeGenericSelectors(url: String, classesJson: String, idsJson: String): String

    @Volatile
    private var ready = false

    fun isReady(): Boolean = ready

    fun init(filterDir: String) {
        ready = nativeInit(filterDir)
    }
}
