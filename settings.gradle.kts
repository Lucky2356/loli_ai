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
include(":core")

// :desktop — клиент для Windows (Compose Desktop) на том же ядре. Отключается свойством loli.skipDesktop=true.
if (providers.gradleProperty("loli.skipDesktop").orNull != "true") {
    include(":desktop")
}

// :app — Android-клиент. Можно отключить свойством loli.skipAndroid=true,
// чтобы собирать и тестировать ядро на машине без Android SDK.
val skipAndroid = providers.gradleProperty("loli.skipAndroid").orNull == "true"
if (!skipAndroid) {
    include(":app")
}
