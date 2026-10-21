import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AI providers layer (IRON-46): pure Kotlin/JVM, with no Android or UI dependencies, so a new
// provider is added here without touching the rules engine or the screens (RNF-MAN-04).
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
    // HTTP for the cloud providers (IRON-47): the same client on the JVM and on Android.
    api(libs.okhttp)
    // The action catalog becomes the AI's tools (IRON-48); the engine knows nothing of the AI.
    api(project(":engine"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // Tests tagged "live" call the real APIs: they run only with `liveTest`.
    useJUnitPlatform { excludeTags("live") }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Talks to the real providers with the keys of the environment (DEEPSEEK_API_KEY), outside `check`:
// `.\gradlew.bat :ai:liveTest`. Each test is skipped when its key is missing.
tasks.register<Test>("liveTest") {
    description = "Runs the tests that call the real AI providers."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Same coverage floor as the engine: `check` fails below 95% of lines.
kover {
    // The live tests are not part of the coverage, so `check` never calls the real APIs.
    currentProject {
        instrumentation {
            disabledForTestTasks.add("liveTest")
        }
    }
    reports {
        verify {
            rule {
                minBound(95)
            }
        }
    }
}
