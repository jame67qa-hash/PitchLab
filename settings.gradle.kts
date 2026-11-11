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
        // MediaPipe Tasks 发布在 Google Maven；OpenCV 官方 AAR 发布在 Maven Central
    }
}

rootProject.name = "PitchLab"
include(":app")
