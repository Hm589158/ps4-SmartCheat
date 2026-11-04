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
    // Plugin versions are declared here (not applied) so that the pure-JVM modules
    // can be built in environments that cannot reach Google's Maven repository.
    plugins {
        id("com.android.application") version "8.11.1"
        id("org.jetbrains.kotlin.android") version "2.2.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
        id("org.jetbrains.kotlin.jvm") version "2.2.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "PS4-SmartCheat"

include(":core")
include(":mockserver")

// The Android application module needs an Android SDK. It is included whenever an
// SDK is available (CI, Android Studio) and can be excluded explicitly with
// -PskipAndroid=true. Without an SDK only :core and :mockserver are built.
val skipAndroid = providers.gradleProperty("skipAndroid").orNull == "true"
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    localProps.getProperty("sdk.dir") != null
if (!skipAndroid && sdkAvailable) {
    include(":app")
} else {
    logger.lifecycle("PS4-SmartCheat: Android SDK not found or skipAndroid=true -> building :core and :mockserver only")
}
