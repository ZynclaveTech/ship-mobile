// The sample is an app, never published: no maven-publish here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zynclave.shipsample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zynclave.shipsample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        // The runtime Ship matches releases against is "android-" + versionName.
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":ship-core"))
    implementation(project(":ship-ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
}
