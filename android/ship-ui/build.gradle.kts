plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.zynclave.ship.ui"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
        }
    }
}

// The definitions corpus and signing vectors at the repo root, shared with the iOS tests.
tasks.withType<Test>().configureEach {
    val testdata = rootDir.parentFile.resolve("testdata")
    inputs.dir(testdata).withPropertyName("testdata")
    systemProperty("ship.testdata", testdata.absolutePath)
}

androidComponents {
    // The unit tests run against the debug variant, which carries the Compose test manifest.
    beforeVariants(selector().withBuildType("release")) { it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false }
}

kotlin {
    // Every public declaration says so: nothing becomes API by accident.
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    api(project(":ship-core"))
    // JsonObject appears in the public API (Node.Custom).
    api(libs.kotlinx.serialization.json)

    // Appear in the public API: Modifier, Color, @Composable, StateFlow, LottieCompositionResult.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.coroutines.core)
    api(libs.lottie.compose)

    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.exifinterface)
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
