# ── Stack traces: keep line numbers for crash reports ───────────────────────────
-keepattributes SourceFile,LineNumberTable,Exceptions,InnerClasses,Signature
-renamesourcefileattribute SourceFile

# ── Disable obfuscation and optimization ─────────────────────────────────────────
-dontobfuscate
-dontoptimize

# ── youtubedl-android + FFmpeg (JNI — must not be removed by shrinking) ──────────
-keep class com.yausername.** { *; }
-keepclassmembers class com.yausername.** { *; }
-dontwarn com.yausername.**

# ── Apache Commons Compress (transitive dep of youtubedl-android) ─────────────────
# ZipUtils.unzip() registers extra field handlers (e.g. AsiExtraField) at runtime
# via a factory — R8 shrinker removes them as "unused" → ExceptionInInitializerError
-keep class org.apache.commons.compress.** { *; }
-keepclassmembers class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.**

# ── ViewModel factory (instantiated via reflection) ──────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ── JSON ─────────────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── youtubedl-android + FFmpeg (JNI + heavy reflection) ─────────────────────────
# The library loads native .so by class name at runtime — must not be renamed.
-keep class com.yausername.** { *; }
-keepclassmembers class com.yausername.** { *; }
-keepclasseswithmembernames class com.yausername.** {
    native <methods>;
}
-dontwarn com.yausername.**
-dontwarn io.github.junkfood02.**

# ── libtorrent4j (JNI + SWIG bindings) ──────────────────────────────────────────
-keep class org.libtorrent4j.** { *; }
-keepclassmembers class org.libtorrent4j.** { *; }
-keepclasseswithmembernames class org.libtorrent4j.** {
    native <methods>;
}
-keep class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**

# ── Coil (registered via ServiceLoader) ──────────────────────────────────────────
-dontwarn coil.**

# ── Kotlin coroutines (volatile fields used by coroutine machinery) ───────────────
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── ViewModel factory (instantiated via reflection) ──────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(android.app.Application);
    <init>();
}

# ── JSON ─────────────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Remove Log calls in release ──────────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}