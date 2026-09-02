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

rootProject.name = "life-insights"

include(":insights")
include(":sync-model")
include(":server")

// The server is built inside a container that has no Android SDK, and Gradle configures every
// module in this file before it compiles anything, so including :app there would fail on a missing
// SDK before a single line of server code was touched.
//
// This is an explicit switch rather than a check for whether an SDK happens to be installed: a
// developer with no SDK should get a clear error telling them to install one, not a build that
// quietly leaves the app out.
if (System.getenv("LIFE_INSIGHTS_SERVER_ONLY") != "1") {
    include(":app")
}
