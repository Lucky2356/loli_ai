import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/** Номер версии для установщика: только цифры «X.Y.Z» (так требует MSI); «2.9.0-beta.1» → «2.9.0». */
val desktopVersion = (providers.gradleProperty("loli.desktopVersion").orNull ?: "2.9.0").substringBefore('-')

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "ai.loli.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Loli"
            packageVersion = desktopVersion
            // Метаданные установщика — латиницей: WiX без настройки кодовой страницы не принимает кириллицу.
            description = "Loli personal assistant"
            vendor = "Lucky2356"
            // java.sql — SQLite через JDBC, jdk.crypto.ec — HTTPS к Supabase и AI.
            modules("java.sql", "jdk.crypto.ec", "java.net.http")
            windows {
                menu = true
                menuGroup = "Loli"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Постоянный идентификатор: новая версия ставится поверх старой, а не рядом.
                upgradeUuid = "6f1c2b8e-4c1a-4f5e-9d2b-7a3e0c5d8b41"
            }
        }
    }
}
