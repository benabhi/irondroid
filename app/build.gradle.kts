plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.irondroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.irondroid"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the development-only tools, such as the AI test screen (IRON-47).
        buildConfig = true
    }

    // Lists the bundled languages so Android 13+ offers them in the per-app language settings.
    androidResources {
        generateLocaleConfig = true
    }

    lint {
        error += "HardcodedText"
        error += "MissingTranslation"
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":data"))
    implementation(project(":ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
