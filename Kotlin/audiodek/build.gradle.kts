// audioDEK: the pure Kotlin ALAC decoder. A plain JVM library (Kotlin stdlib only: no
// Android, Media3, coroutines or Compose), used by the Android app through :shared.
// See README.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Java 17 bytecode whatever JDK builds it (as :shared and Android).
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    testImplementation(kotlin("test"))
}
