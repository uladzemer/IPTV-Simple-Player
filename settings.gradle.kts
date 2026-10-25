// Сборка «Просто ТВ»: приложение для приставки и его ядро.

pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Репозитории задаются только здесь, модули своих не добавляют.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SimpleTV"

include(":app:tv")
include(":engine")
