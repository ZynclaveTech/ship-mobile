plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
}

val shipVersion = "0.1.0"

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates("com.zynclave.ship", project.name, shipVersion)

            pom {
                name.set("Zynclave Ship ${project.name.removePrefix("ship-")}")
                description.set(
                    if (project.name == "ship-core") {
                        "Zynclave Ship client: verified over-the-air content (strings, theme, flags) for Android apps."
                    } else {
                        "Zynclave Ship UI: server-driven cards and popups for Jetpack Compose."
                    },
                )
                url.set("https://github.com/ZynclaveTech/ship-mobile")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("zynclave")
                        name.set("Zynclave")
                        url.set("https://github.com/ZynclaveTech")
                    }
                }
                scm {
                    url.set("https://github.com/ZynclaveTech/ship-mobile")
                    connection.set("scm:git:https://github.com/ZynclaveTech/ship-mobile.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ZynclaveTech/ship-mobile.git")
                }
            }

            // Credentials (mavenCentralUsername / mavenCentralPassword) and the signing
            // key are read from Gradle properties or ORG_GRADLE_PROJECT_* environment
            // variables, e.g. ~/.gradle/gradle.properties. With no key configured the
            // artifacts are left unsigned, so publishToMavenLocal works anywhere.
            publishToMavenCentral()
            val signingConfigured = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
                .any { providers.gradleProperty(it).isPresent }
            if (signingConfigured) signAllPublications()
        }
    }
}
