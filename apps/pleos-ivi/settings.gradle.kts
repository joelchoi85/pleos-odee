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
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
        // LiveKit 의존성 audioswitch 전용
        maven { url = uri("https://jitpack.io"); content { includeGroup("com.github.davidliu") } }
    }
}

rootProject.name = "pleos-syncdrive-ivi"
include(":app")
