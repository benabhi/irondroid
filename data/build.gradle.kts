// Persistence layer: saves and loads campaigns.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.irondroid.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":engine"))
}
