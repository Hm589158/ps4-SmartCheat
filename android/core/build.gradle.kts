plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    // x86-64 decoder/formatter (MIT). Text is rendered on the phone; ps4debug-NG's disassembler sends no mnemonics.
    implementation(libs.iced.x86)
    testImplementation(project(":mockserver"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Benchmarks are slow and machine-dependent: excluded from `test`, run with `./gradlew :core:benchmark`.
tasks.named<Test>("test") {
    exclude("**/ScanBenchmark*")
}

tasks.register<Test>("benchmark") {
    description = "Compares Turbo / server-side / Android scan backends on an identical mock dataset."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*ScanBenchmark*") }
    systemProperty("benchmark.out", layout.buildDirectory.dir("reports/benchmark").get().asFile.absolutePath)
    maxHeapSize = "1g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
