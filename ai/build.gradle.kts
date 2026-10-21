import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AI providers layer (IRON-46): pure Kotlin/JVM, with no Android, engine or UI dependencies, so a
// new provider is added here without touching the rules engine or the screens (RNF-MAN-04).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

// Same bytecode level as the Android modules that consume it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Same coverage floor as the engine: `check` fails below 95% of lines.
kover {
    reports {
        verify {
            rule {
                minBound(95)
            }
        }
    }
}
