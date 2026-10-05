package com.mkaafi6.muufi

import org.json.JSONObject

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

    /**
     * Builds the engine from every *.txt file in [filterDir] and loads the
     * `$redirect`/scriptlet resources from [resourcesPath].
     *
     * Returns a JSON status string: `{"ok":true,"lists":N,"resources":M}` or
     * `{"ok":false,"error":"..."}`.
     */
    external fun nativeInit(filterDir: String, resourcesPath: String): String

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
     * `$removeparam` support. Returns a rewritten URL for a top-level
     * navigation, or "" when nothing should change.
     */
    external fun nativeRewrite(
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

    /** Initializes the engine and updates [ready]. Returns the status JSON. */
    fun init(filterDir: String, resourcesPath: String): String {
        val status = try {
            nativeInit(filterDir, resourcesPath)
        } catch (t: Throwable) {
            "{\"ok\":false,\"error\":" + JSONObject.quote(t.message ?: "native error") + "}"
        }
        ready = try {
            JSONObject(status).optBoolean("ok", false)
        } catch (t: Throwable) {
            false
        }
        return status
    }

    /** Builds a status JSON object without initializing (for the error path). */
    fun failure(message: String): String =
        "{\"ok\":false,\"error\":" + JSONObject.quote(message) + "}"
}
