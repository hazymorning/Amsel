// Amsel's build file for the vendored Sendspin module. Everything else in this directory is a
// verbatim copy of music-assistant/mobile-app's sendspin-kmp (see VENDORED.md): never edit it,
// re-run scripts/vendor-sendspin.sh. Only this file is ours. Upstream builds the same sources as
// Kotlin Multiplatform with Android and iOS targets; we keep the multiplatform plugin with the
// Android target alone, so commonMain/androidMain and the one expect/actual compile unchanged.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    explicitApi()
    // Warnings in vendored code cannot be fixed here (the sources are never edited).
    compilerOptions { suppressWarnings.set(true) }

    android {
        namespace = "io.music_assistant.sendspin"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        optimization {
            // -dontwarn for the BouncyCastle classes R8 strips; ships to :app through the AAR.
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            // Public API types: StateFlow, Flow, CoroutineDispatcher, HttpClient.
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            // Upstream takes kotlinx-serialization-json through ktor-serialization-kotlinx-json;
            // the sources use only kotlinx.serialization, so we depend on it directly.
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kermit)
            implementation(libs.cryptography.core)
        }
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            // X25519 below API 33 (lightweight RFC 7748 math only; R8 strips the rest).
            implementation(libs.bouncycastle.provider)
            // Opus decoding in AndroidDecoderFactory.
            implementation(libs.concentus)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
        }
    }
}
