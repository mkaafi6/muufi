# Keep the JNI bridge class and its native methods.
-keep class com.mkaafi6.muufi.AdBlocker { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
