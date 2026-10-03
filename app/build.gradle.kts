plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from CI secrets (see README). Without them the
// release build falls back to the debug key so it can still be installed.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")

// The accounts service (docs/accounts/PLAN.md): the repository variable
// ACCOUNT_URL in CI. Without it the app has no accounts.
val accountUrl: String = System.getenv("ACCOUNT_URL").orEmpty().trim().also {
    require(it.isEmpty() || Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?/?").matches(it)) {
        "ACCOUNT_URL must be like https://sub.example.com"
    }
}

android {
    namespace = "com.klausms.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.klausms.vpn"
        minSdk = 26
        targetSdk = 37
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
        buildConfigField("String", "ACCOUNT_URL", "\"$accountUrl\"")

        // One APK for every phone (Android 8+, 64- and 32-bit ARM).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Compressed in the APK and extracted on install: the single APK
            // carries two copies of the core, so this halves the download.
            // The libraries themselves are 16 KB page aligned (see CI).
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        // Screenshot tests draw the real resources (fonts, map, icons).
        unitTests.isIncludeAndroidResources = true
        // Plain JVM tests reach android.util.Log and SystemClock through the
        // code they test: those return 0/null instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

// ScreenshotTest draws every screen with Robolectric: slow, and never a
// reason to fail the build. Without -Pscreenshots every other test runs;
// with it, only the screenshots (CI runs both, see android.yml).
val screenshots = providers.gradleProperty("screenshots").isPresent

tasks.withType<Test>().configureEach {
    filter {
        if (screenshots) includeTestsMatching("*.ScreenshotTest") else excludeTestsMatching("*.ScreenshotTest")
    }
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    // Real HWUI rendering: shadows and layers look as on a phone.
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    systemProperty("java.awt.headless", "true")
    // Robolectric reaches into JDK internals on Java 17+.
    jvmArgs(
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
    maxHeapSize = "3g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// A library that ships in the APK must also be listed on the Licenses screen (LicensesScreen.kt).
dependencies {
    // Xray core, built from ../libxray by gomobile (see scripts/build-libxray.sh).
    implementation(files("libs/libxray.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // QR codes with keys: the camera (CameraX) and an offline decoder (ZXing).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Test-only activity for the screenshot tests; debug builds only.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
