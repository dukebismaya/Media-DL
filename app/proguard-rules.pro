# ── Stack traces: keep line numbers for crash reports ───────────────────────────
-keepattributes SourceFile,LineNumberTable,Exceptions,InnerClasses,Signature
-renamesourcefileattribute SourceFile

# ── App: keep ALL classes in our own package ─────────────────────────────────────
# Without this, R8 renames MainActivity/MediaDLApplication and the app crashes.
-keep class com.bismaya.mediadl.** { *; }
-keepclassmembers class com.bismaya.mediadl.** { *; }

# ── youtubedl-android + FFmpeg (JNI + heavy reflection) ─────────────────────────
# The library loads native .so by class name at runtime — must not be renamed.
-keep class com.yausername.** { *; }
-keepclassmembers class com.yausername.** { *; }
-keepclasseswithmembernames class com.yausername.** {
    native <methods>;
}
-dontwarn com.yausername.**
-dontwarn io.github.junkfood02.**

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