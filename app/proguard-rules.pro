# ── Stack traces: keep line numbers for crash reports ───────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── youtubedl-android (JNI + reflection) ────────────────────────────────────────
-keep class com.yausername.** { *; }
-keep class com.github.junkfood02.** { *; }
-keep class io.github.junkfood02.** { *; }
-dontwarn com.yausername.**
-dontwarn io.github.junkfood02.**

# ── Coil image loading ───────────────────────────────────────────────────────────
-dontwarn coil.**
-keep class coil.** { *; }

# ── Kotlin ───────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { *; }
-keepclassmembers class kotlin.coroutines.** { *; }
-dontwarn kotlin.**

# ── AndroidX / Jetpack Compose ───────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── ViewModel — keep all ViewModel subclasses ────────────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel { *; }

# ── App data classes (used in JSON parsing + SharedPrefs) ────────────────────────
-keep class com.bismaya.mediadl.DownloadRecord { *; }
-keep class com.bismaya.mediadl.VideoInfo { *; }
-keep class com.bismaya.mediadl.VideoFormat { *; }

# ── JSON ─────────────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Remove all android.util.Log calls in release ─────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
#-renamesourcefileattribute SourceFile