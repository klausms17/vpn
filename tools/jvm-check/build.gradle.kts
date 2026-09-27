// Compiles the app's plain-Kotlin code (everything but the Compose UI) on
// the JVM against android-all and small stubs, and runs the unit tests.
// For machines without the Android SDK; CI still builds the real APK.
// Run: gradle -p tools/jvm-check test
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
}

kotlin {
    jvmToolchain(21)
    // android-all's nullability annotations are warnings in the Android
    // build too (getSystemService and friends).
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xnullability-annotations=@android.annotation:warn",
            "-Xnullability-annotations=@androidx.annotation:warn",
        )
    }
}

val app = rootDir.resolve("../../app/src")

sourceSets {
    main {
        kotlin.srcDirs("stubs", app.resolve("main/java"))
        java.srcDirs("stubs")
        // Compose screens need the Android build.
        kotlin.exclude(
            "com/klausms/vpn/ui/MainActivity.kt",
            "com/klausms/vpn/ui/components/**",
            "com/klausms/vpn/ui/screens/**",
            "com/klausms/vpn/ui/theme/**",
        )
    }
    test {
        kotlin.srcDirs(app.resolve("test/java"))
        kotlin.exclude("**/ScreenshotTest.kt")
        // android.util.Log and SystemClock return defaults, as with
        // unitTests.isReturnDefaultValues in the app build.
        java.srcDirs("teststubs")
    }
}

dependencies {
    compileOnly("org.robolectric:android-all:15-robolectric-12714715")
    testCompileOnly("org.robolectric:android-all:15-robolectric-12714715")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.withType<Test>().configureEach {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
