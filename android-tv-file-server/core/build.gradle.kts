plugins {
    alias(libs.plugins.kotlin.jvm)
}

// This module is deliberately Android-free: it is a plain Kotlin/JVM library so the whole
// protocol stack can be compiled and unit-tested without the Android SDK. It still has to
// *run* on Android 8.0 (API 26), so only Java 8 APIs plus java.time may be used — see the
// api guard documented in the project README.
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

sourceSets {
    named("main") { java.srcDirs("src/main/kotlin") }
    named("test") { java.srcDirs("src/test/kotlin") }
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Socket-based integration tests bind on the loopback interface only.
    systemProperty("java.net.preferIPv4Stack", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}
