plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("amsel.robolectric")
}

android {
    namespace = "io.github.hazymorning.amsel.data"
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
    api(project(":api"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    testImplementation(testFixtures(project(":api")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
