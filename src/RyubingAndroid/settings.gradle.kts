pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "RyubingAndroid"

// :app       - the Kotlin/Compose APK + JNI shim (libryubingjni.so)
// :libryubing - wraps `dotnet publish` of LibRyubing into libryubing.so
include(":app")
include(":libryubing")
