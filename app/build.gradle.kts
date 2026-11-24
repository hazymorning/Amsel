plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    id("amsel.budgets")
    id("amsel.robolectric")
}

android {
    namespace = "io.github.hazymorning.amsel"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.hazymorning.amsel"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        val version = System.getenv("AMSEL_VERSION_NAME") ?: rootProject.file("release/VERSION").readText()
        versionName = version.trim().removePrefix("v")
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
    }

    signingConfigs {
        // Without AMSEL_KEYSTORE_PATH the release APK stays unsigned; it is never signed with the debug key.
        val storePath = System.getenv("AMSEL_KEYSTORE_PATH")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = System.getenv("AMSEL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AMSEL_KEY_ALIAS")
                keyPassword = System.getenv("AMSEL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
            // Only Google Play reads the embedded git revision, as it does the dependency blob below.
            vcsInfo { include = false }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The app's languages only, so a library's own translation never mixes in a third one.
        localeFilters += listOf("en", "de")
        // OkHttp's public suffix list (130 KB) is read only to check cookie domains; no client here keeps cookies.
        ignoreAssetsPatterns += "!PublicSuffixDatabase.list"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/*.version",
            "DebugProbesKt.bin",
            // X25519 uses only BouncyCastle's RFC 7748 math; its 1.2 MB of data files are never read.
            "org/bouncycastle/**",
            // Read only by kotlin-reflect, which the app does not ship.
            "kotlin/**.kotlin_builtins",
            // GraalVM's configuration, for native images only.
            "META-INF/native-image/**",
            // AndroidX's Apache 2.0 copies and the protobuf and Bouncy Castle licences: each ships in assets/licences.
            "META-INF/androidx/**/LICENSE.txt",
            "META-INF/LICENSE.md",
        )
        // Only multi-process DataStore uses it; every DataStore here lives in one process.
        jniLibs.excludes += "**/libdatastore_shared_counter.so"
    }

    lint {
        warningsAsErrors = true
        // Pull requests lint already; the release build skips it.
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Hardware rendering, so the covers' soft shadows render as on a phone.
        unitTests.all { test ->
            test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // -Plook=<qualifiers>, such as de-w360dp: see ScreenshotTest.
            providers.gradleProperty("look").orNull?.let { test.systemProperty("amsel.look", it) }
            // Tests read these from the source tree; as inputs, a change to them reruns the tests.
            for (read in listOf("../docs/design/readme", "../playback/src/main/res")) {
                test.inputs.dir(read).withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
    }

    dependenciesInfo {
        // Keeps the APK free of the Google-encrypted dependency blob (F-Droid/Obtainium friendly).
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":playback"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.profileinstaller)
    // A cover's hue, which tints Now Playing, item pages' heads and Continue listening's cards.
    implementation(libs.androidx.palette)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(testFixtures(project(":api")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
