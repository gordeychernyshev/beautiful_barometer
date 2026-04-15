pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // JitPack тут редко нужен, оставь только если реально ставишь плагины с JitPack:
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // ✅ Корректное подключение JitPack
        maven(url = uri("https://jitpack.io")) {
            name = "JitPack"

            // Рекомендуется ограничить группы, которые реально нужны
            content {
                includeGroup("com.github.PhilJay")   // MPAndroidChart
                // добавляй другие группы тут только при необходимости
            }
        }
    }
}

rootProject.name = "BeautifulBarometer"
include(":app")

