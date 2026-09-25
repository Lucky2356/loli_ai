// Корневой build-скрипт. Все плагины объявлены здесь (apply false), чтобы
// Kotlin Gradle Plugin и Android Gradle Plugin жили в одном classloader.
buildscript {
    val skipAndroid = providers.gradleProperty("loli.skipAndroid").orNull == "true"
    dependencies {
        if (!skipAndroid) {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}
