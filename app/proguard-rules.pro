# Project ProGuard/R8 rules. Compose, OkHttp, kotlinx.serialization, and
# ML Kit ship their own consumer rules; these cover the app's own edges.

# --- JNI / embedded Arti -----------------------------------------------------
# The Rust symbols hardcode com/paulscode/lightningfork/net/ArtiNative and the
# native method names, so neither the class nor those methods may be renamed.
-keep class com.paulscode.lightningfork.net.ArtiNative { *; }
-keepclasseswithmembernames class * { native <methods>; }

# --- kotlinx.serialization (belt-and-suspenders over the shipped rules) -------
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.paulscode.lightningfork.** {
    static <fields>;
    *** Companion;
    static **$* *;
}
-keepclasseswithmembers @kotlinx.serialization.Serializable class com.paulscode.lightningfork.** {
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep, includedescriptorclasses class com.paulscode.lightningfork.**$$serializer { *; }
