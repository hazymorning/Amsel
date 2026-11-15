plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// The root's own scripts too, and build-logic's, which as an included build no task name reaches.
ktlint { version.set(libs.versions.ktlint.get()) }
tasks.named("ktlintCheck") { dependsOn(gradle.includedBuild("build-logic").task(":ktlintCheck")) }

subprojects {
    // The vendored Sendspin module is a verbatim upstream copy: never linted or reformatted, and its
    // compiler warnings are upstream's to fix. Everywhere else they fail the build, as lint's do.
    if (path != ":sendspin") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(rootProject.libs.versions.ktlint.get())
            filter {
                exclude { it.file.path.contains("/build/") }
            }
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions.allWarningsAsErrors.set(true)
        }
    }
    tasks.withType<Test>().configureEach {
        testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // Robolectric reaches into these on JDK 17+.
        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
        )
    }
}
