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
        // Only the OCR engine (full flavour) comes from JitPack; nothing else may.
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}
rootProject.name = "looseleaf"
include(":app")
