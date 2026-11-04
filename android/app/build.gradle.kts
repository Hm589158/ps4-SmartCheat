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
        versionCode = ciVersionCode ?: 4
        versionName = ciVersionName ?: "0.4.0"
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
}
