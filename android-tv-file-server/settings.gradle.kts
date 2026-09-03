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

rootProject.name = "TvFileServer"

// :core is a plain Kotlin/JVM library with no Android dependencies. It holds the whole
// HTTP / WebDAV / FTP protocol stack and can be built and tested without the Android SDK.
include(":core")

// :app is the Android TV front end: Leanback UI, foreground service, storage discovery.
include(":app")
