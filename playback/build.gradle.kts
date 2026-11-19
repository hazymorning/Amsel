plugins {
    alias(libs.plugins.android.library)
    id("amsel.robolectric")
}

android {
    namespace = "io.github.hazymorning.amsel.playback"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { warningsAsErrors = true }
}

dependencies {
    api(project(":data"))
    implementation(libs.kotlinx.coroutines.android)
    api(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.core)
    // "This phone": the vendored Sendspin player, on a Ktor client over our own OkHttpClient.
    implementation(project(":sendspin"))
    implementation(libs.ktor.client.okhttp)
    // Only to quiet the module's logging in release builds (already on the allowlist through it).
    implementation(libs.kermit)

    testImplementation(testFixtures(project(":api")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
