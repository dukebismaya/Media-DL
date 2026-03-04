import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read signing credentials from keystore.properties (not committed to git)
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.bismaya.mediadl"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.bismaya.mediadl"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.2.260304"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        multiDexEnabled = true

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                keyAlias     = keystoreProps["keyAlias"]     as String
                keyPassword  = keystoreProps["keyPassword"]  as String
                storeFile    = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        // Required for java.time APIs on API < 26 (used by Room & libretorrent session layer)
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

dependencies {
    // Core library desugaring (java.time APIs on API < 26)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // ── Media download (yt-dlp / ffmpeg) ────────────────────────────────────
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // ── AndroidX ────────────────────────────────────────────────────────────
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.8.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.work:work-runtime:2.11.0")
    // Fix for WorkManager https://github.com/google/ExoPlayer/issues/7993
    implementation("com.google.guava:guava:33.5.0-jre")

    // ── Room (libretorrent storage layer) ───────────────────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-rxjava3:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")

    // ── ReactiveX (used extensively by libretorrent session layer) ──────────
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")

    // ── BitTorrent engine (libtorrent4j) — upgraded to match libretorrent ───
    // Only arm64 to keep APK lean; add other ABIs if needed
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")

    // ── Apache Commons (used by TorrentSessionImpl, TorrentMetaInfo) ────────
    // Do NOT upgrade commons-io >= 2.6 (uses Java NIO, requires API >= 26)
    //noinspection GradleDependency
    implementation("commons-io:commons-io:2.5")
    implementation("org.apache.commons:commons-text:1.14.0")

    // ── Preference (used by Settings layer) ─────────────────────────────────
    implementation("androidx.preference:preference:1.2.1")

    // ── Coroutines → RxJava3 bridge (TorrentBridge.kt uses Flowable.asFlow()) ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.8.1")

    // ── Moshi (SettingsRepositoryImpl serialises preferences as JSON) ────────
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.moshi:moshi-adapters:1.15.2")

    // ── Gson (FeedRepositoryImpl) ────────────────────────────────────────────
    implementation("com.google.code.gson:gson:2.13.2")

    // ── jurl (NormalizeUrl / PercentEncoder URL normalizer) ──────────────────
    implementation("com.github.anthonynsimon:jurl:v0.4.2")

    // ── JNA (TorrentInputStream native calls) ────────────────────────────────
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // ── AppCompat (applyNightMode in libretorrent via AppCompatDelegate) ─────
    implementation("androidx.appcompat:appcompat:1.7.1")
}
