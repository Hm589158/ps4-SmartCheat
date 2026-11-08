plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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
    // Used by the mock to emulate the server-side assembler and cross-reference search.
    implementation(libs.iced.x86)
}

application {
    mainClass.set("com.ps4smartcheat.mockserver.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
