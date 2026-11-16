plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zynclave.ship"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric's SDK 37 sandbox reaches into FileDescriptor through
            // jdk.internal.access, which java.base does not export.
            all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
        }
    }

}

androidComponents {
    // The unit tests read fixture strings from src/debug/res, which only the
    // debug variant has; there is nothing for a release-variant test run to add.
    beforeVariants(selector().withBuildType("release")) { it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // HttpUrl appears in the public API (ShipConfig, ShipTransport).
    api(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
