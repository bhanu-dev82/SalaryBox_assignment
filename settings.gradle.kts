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

rootProject.name = "BhanuAttendance"

include(":app")
include(":core:common")
include(":core:designsystem")
include(":domain")
include(":data")
include(":feature:auth")
include(":feature:admin")
include(":feature:staff")
