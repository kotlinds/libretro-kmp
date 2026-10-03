import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import java.io.File

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven)
}

mavenPublishing {
    // Empty javadoc jars: embedding the full Dokka site in each platform publication
    // would blow the Maven Central size limits. KDoc stays available in the IDE via sources jars.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("libretro-kmp")
        description.set("Kotlin Multiplatform frontend for libretro cores")
        url.set(project.ext.get("url")?.toString())
        licenses {
            license {
                name.set(project.ext.get("license.name")?.toString())
                url.set(project.ext.get("license.url")?.toString())
            }
        }
        developers {
            developer {
                id.set(project.ext.get("developer.id")?.toString())
                name.set(project.ext.get("developer.name")?.toString())
                email.set(project.ext.get("developer.email")?.toString())
                url.set(project.ext.get("developer.url")?.toString())
            }
        }
        scm {
            url.set(project.ext.get("scm.url")?.toString())
        }
    }
}

val libsDir = File(rootDir, "libs")
val includeDir = File(libsDir, "include")

android {
    namespace = "dev.kotlinds.libretrokmp"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
        }
    }
}

kotlin {
    // Tiers are in accordance with <https://kotlinlang.org/docs/native-target-support.html>
    // Native targets only need libretro.h: cores are shared libraries loaded at runtime (dlopen /
    // LoadLibrary), so nothing is linked at build time.
    fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.libretroInterop() {
        val main by compilations.getting
        main.cinterops.create("libretro") {
            definitionFile = file("src/nativeInterop/cinterop/libretro.def")
            includeDirs.headerFilterOnly(includeDir)
        }
    }

    // Tier 1
    macosArm64 { libretroInterop() }
    iosSimulatorArm64 { libretroInterop() }

    // Tier 2
    linuxX64 { libretroInterop() }
    linuxArm64 { libretroInterop() }
    iosArm64 { libretroInterop() }

    // Tier 3
    mingwX64 { libretroInterop() }

    // Android
    androidTarget {
        publishLibraryVariants("release")
    }

    // jvm
    jvmToolchain(21)
    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.jna)
        }

        // nativeMain covers all native targets: macOS + Linux + Windows + iOS.
        // Loading a shared library differs between POSIX (dlopen) and Windows (LoadLibrary).
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val posixMain by creating { dependsOn(nativeMain) }
        val macosArm64Main by getting { dependsOn(posixMain) }
        val linuxX64Main by getting { dependsOn(posixMain) }
        val linuxArm64Main by getting { dependsOn(posixMain) }
        val iosArm64Main by getting { dependsOn(posixMain) }
        val iosSimulatorArm64Main by getting { dependsOn(posixMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("${rootProject.projectDir}/detekt.yml")
    source.from(file("src/commonMain/kotlin"))
}
