pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "loli"

// :core — платформенно-независимое ядро (Kotlin/JVM): домен, AI, память, синхронизация.
// Его же будет использовать будущий Windows-клиент (Compose Desktop / JVM).
include(":core")

// :app — Android-клиент. Можно отключить свойством loli.skipAndroid=true,
// чтобы собирать и тестировать ядро на машине без Android SDK.
val skipAndroid = providers.gradleProperty("loli.skipAndroid").orNull == "true"
if (!skipAndroid) {
    include(":app")
}
