plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    id("amsel.budgets")
}

android {
    namespace = "io.github.hazymorning.amsel"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.hazymorning.amsel"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // The version being released (v0.9.0-beta.1 -> 0.9.0-beta.1): the workflow's, else release/VERSION.
        val version = System.getenv("AMSEL_VERSION_NAME") ?: rootProject.file("release/VERSION").readText()
        versionName = version.trim().removePrefix("v")
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
    }

    signingConfigs {
        // Release signing comes only from the environment (CI secrets or a local shell).
        // Without it the release build is left unsigned; it is never signed with a debug key.
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
            // The git revision, which only Google Play reads (as the dependency blob below).
            vcsInfo { include = false }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Only the app's languages, so a library's strings never appear in a third one.
        localeFilters += listOf("en", "de")
        // OkHttp's public suffix list (130 KB) is read only to check cookie domains, and no client
        // here keeps cookies.
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
            // The same Apache 2.0 text once per AndroidX library, DataStore's protobuf licence and
            // Bouncy Castle's: each ships once in assets/licences.
            "META-INF/androidx/**/LICENSE.txt",
            "META-INF/LICENSE.md",
        )
        // DataStore's counter for several processes sharing one file; each of the app's has one.
        jniLibs.excludes += "**/libdatastore_shared_counter.so"
    }

    lint {
        warningsAsErrors = true
        // Every pull request lints with warnings as errors; a release build need not again.
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Screenshots draw through Android's hardware pipeline, so the covers' soft shadows show as
        // they do on a phone.
        unitTests.all { test ->
            test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // -Plook=de-w360dp: every shot in German on a narrow phone, into build/look (ScreenshotTest).
            providers.gradleProperty("look").orNull?.let { test.systemProperty("amsel.look", it) }
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
    // A cover's hue, for Now Playing's glow and Continue listening's card.
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
