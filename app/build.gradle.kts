import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Конфигурация берётся из переменных окружения (GitHub Secrets / Variables)
 * или из gradle-свойств (-P). В репозитории секретов нет.
 */
fun config(name: String): String =
    (System.getenv(name) ?: providers.gradleProperty(name).orNull ?: "").trim()

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseKeystoreFile = config("LOLI_KEYSTORE_FILE")
val hasReleaseSigning = releaseKeystoreFile.isNotEmpty() && file(releaseKeystoreFile).exists()

android {
    namespace = "ai.loli.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.loli.app"
        minSdk = 26
        targetSdk = 35
        versionCode = config("LOLI_VERSION_CODE").toIntOrNull() ?: 1
        versionName = config("LOLI_VERSION_NAME").ifEmpty { "1.0.0" }

        buildConfigField("String", "SUPABASE_URL", config("SUPABASE_URL").asBuildConfigString())
        buildConfigField("String", "SUPABASE_ANON_KEY", config("SUPABASE_ANON_KEY").asBuildConfigString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = config("LOLI_KEYSTORE_PASSWORD")
                keyAlias = config("LOLI_KEY_ALIAS")
                keyPassword = config("LOLI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Если в CI передан постоянный ключ — подписываем им и debug-сборку,
            // чтобы обновления ставились поверх без потери данных.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
        jniLibs {
            // Нативные библиотеки (SQLCipher, Vosk) не сжимаем — так они грузятся напрямую из APK.
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
