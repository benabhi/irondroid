import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM rules engine: no Android dependencies, so it can be tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
