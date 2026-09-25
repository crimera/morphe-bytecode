import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm") version "2.2.21"
    `maven-publish`
}

group = "crimera"
version = providers.gradleProperty("bytecodeVersion").getOrElse("0.1.0")
description = "Typed Dalvik bytecode emission for Morphe patches"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    // The patcher API this library emits into (MutableMethod, PatchException, findFreeRegister).
    api("app.morphe:morphe-patcher:1.6.0")

    // Patch-side helpers the register search comes from (app.morphe.util).
    api("app.morphe:morphe-patches-library:1.5.0")

    // The dexlib2 fork the builder instructions come from, pinned to the same commit Morphe
    // builds against.
    api("com.github.MorpheApp.smali:smali-dexlib2:d92701d947")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}

// The credit and the licence travel with the artifact, not just the repository.
tasks.jar {
    from("LICENSE", "NOTICE").into("META-INF")
}

tasks.named<Jar>("sourcesJar") {
    from("LICENSE", "NOTICE").into("META-INF")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name = "morphe-bytecode"
                description = "Typed Dalvik bytecode emission for Morphe patches"
                url = "https://github.com/crimera/morphe-bytecode"
                licenses {
                    license {
                        name = "GNU General Public License v3.0 or later"
                        url = "https://www.gnu.org/licenses/gpl-3.0.html"
                    }
                }
                developers {
                    developer {
                        id = "crimera"
                        name = "crimera"
                    }
                }
                scm {
                    url = "https://github.com/crimera/morphe-bytecode"
                    connection = "scm:git:https://github.com/crimera/morphe-bytecode.git"
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/crimera/morphe-bytecode")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN") ?: "")
            }
        }
    }
}
