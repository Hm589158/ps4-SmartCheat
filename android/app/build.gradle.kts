import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes exclusively from environment variables (GitHub Secrets in CI).
// Nothing secret is stored in the repository.
val releaseKeystore: String? = System.getenv("SMARTCHEAT_KEYSTORE_FILE")
val hasReleaseSigning = releaseKeystore != null && file(releaseKeystore).exists()

val ciVersionCode = (System.getenv("SMARTCHEAT_VERSION_CODE") ?: "").toIntOrNull()
val ciVersionName = System.getenv("SMARTCHEAT_VERSION_NAME")

android {
    namespace = "com.ps4smartcheat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ps4smartcheat.app"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 7
        versionName = ciVersionName ?: "0.7.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("SMARTCHEAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SMARTCHEAT_KEY_ALIAS")
                keyPassword = System.getenv("SMARTCHEAT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Explicitly labelled test build: installs next to a release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests {
            // Robolectric UI tests need merged resources; screenshots are written by Roborazzi.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "2g"
                it.testLogging {
                    events("failed")
                    showStandardStreams = true
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(project(":mockserver"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric normally downloads the Android framework jar at test time (and can hit Maven Central rate limits,
// HTTP 429). Resolve it through Gradle instead (cached, retried) and run Robolectric offline from that copy.
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    // Version string matches what Robolectric 4.16.1 requests for SDK 34 (see @Config(sdk = [34])).
    robolectricRuntime("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
}
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val prepareRobolectricJars by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricJars)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}
