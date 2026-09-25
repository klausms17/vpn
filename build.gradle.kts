buildscript {
    dependencies {
        // Built-in Kotlin in AGP 9 uses the newest Kotlin Gradle plugin on
        // the classpath; pin it so the Compose compiler plugin matches.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
