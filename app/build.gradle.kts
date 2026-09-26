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

/** Текущая версия приложения (релиз может передать свою через LOLI_VERSION_NAME). */
val APP_VERSION = "1.8.0"

val releaseKeystoreFile = config("LOLI_KEYSTORE_FILE")
val hasReleaseSigning = releaseKeystoreFile.isNotEmpty() && file(releaseKeystoreFile).exists()

android {
    namespace = "ai.loli.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.loli.app"
        minSdk = 26
        targetSdk = 35
        // Код версии выводится из номера (1.3.0 → 10300): новые релизы всегда ставятся поверх старых.
        versionName = config("LOLI_VERSION_NAME").ifEmpty { APP_VERSION }
        versionCode = versionName!!.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            .let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }
        // Откуда приложение проверяет обновления (в CI — текущий репозиторий).
        buildConfigField("String", "UPDATE_REPO", config("GITHUB_REPOSITORY").ifEmpty { "Lucky2356/loli_ai" }.asBuildConfigString())

        buildConfigField("String", "SUPABASE_URL", config("SUPABASE_URL").asBuildConfigString())
        buildConfigField("String", "SUPABASE_ANON_KEY", config("SUPABASE_ANON_KEY").asBuildConfigString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Телефоны — ARM; без x86-библиотек APK заметно меньше.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Офлайн-модель речи (scripts/fetch-vosk-model.sh) встраивается в APK, если скачана.
    sourceSets.getByName("main").assets.srcDir("vosk-assets")

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
            // Без суффикса пакета: статические ярлыки (res/xml/shortcuts.xml) указывают на ai.loli.app.
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
            // Нативные библиотеки сжимаются в APK (синтезатор речи весит десятки мегабайт) —
            // так обновление скачивается быстрее; при установке Android распаковывает их сам.
            useLegacyPackaging = true
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

// Встроенный «Голос Лоли»: офлайн-синтез sherpa-onnx (Apache-2.0). Библиотека берётся из служебного релиза
// voices-v1 этого репозитория (туда её выкладывает .github/workflows/voices.yml) и проверяется по sha256.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar: File = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    val url = "https://github.com/Lucky2356/loli_ai/releases/download/voices-v1/sherpa-onnx.aar"
    logger.lifecycle("Скачиваю sherpa-onnx $sherpaVersion…")
    uri(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }
    if (digest != sherpaSha256) {
        tmp.delete()
        throw GradleException("sherpa-onnx.aar: неверная контрольная сумма $digest")
    }
    tmp.renameTo(sherpaAar)
}

dependencies {
    implementation(project(":core"))
    implementation(files(sherpaAar))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
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
    // Системный доступ через Shizuku (отладка по Wi-Fi): ассистент по умолчанию и спецвозможности на прошивках с ограничениями.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
