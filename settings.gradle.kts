pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // No JDK 17 is installed on this machine (Android Studio bundles JDK 25) -
    // this lets Gradle auto-download a real JDK 17 to satisfy `jvmToolchain(17)`
    // rather than compiling against an unverified bleeding-edge JDK version.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AwesomeSource"
include(":app")
