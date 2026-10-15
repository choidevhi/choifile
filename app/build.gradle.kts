plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.choidev.choifile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.choidev.choifile"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.6.1"
        // -Pchoifile.abi=arm64-v8a builds a single-ABI APK (smaller download for phones).
        providers.gradleProperty("choifile.abi").orNull?.let { ndk { abiFilters += it.split(",") } }
    }
    // Release key comes from ~/.gradle/gradle.properties (never committed).
    val releaseStore = providers.gradleProperty("CHOIDEV_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("CHOIDEV_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("CHOIDEV_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("CHOIDEV_RELEASE_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    // Lists the app's languages (values, values-ko, values-ja) for Android 13's per-app language setting.
    androidResources {
        generateLocaleConfig = true
        // Only the languages the app is translated into (libraries bring dozens more).
        localeFilters += listOf("en", "ko", "ja")
    }
    packaging {
        // The FFmpeg libraries are ~5.5 MB per ABI; store them compressed (extracted at install) so the
        // sideloaded APK stays about half the size it would be with page-aligned uncompressed libraries.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-video:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    // FFmpeg audio/video decoders for Media3 1.8.0 (GPL-3.0 wrapper, see THIRD_PARTY.md).
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0")

    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.15.0")
    testImplementation("org.mockftpserver:MockFtpServer:3.2.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.slf4j:slf4j-nop:2.0.17")
}
