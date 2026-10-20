import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM rules engine: no Android dependencies, so it can be tested on the JVM.
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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Coverage of the engine tests (IRON-28): `.\gradlew.bat :engine:koverHtmlReport` for the report.
// The build fails if line coverage drops below 95% (`koverVerify`, also run by `check`).
kover {
    reports {
        verify {
            rule {
                minBound(95)
            }
        }
    }
}
