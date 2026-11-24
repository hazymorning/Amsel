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

// A task name given at the root does not reach the included build-logic, so its ktlintCheck is wired in.
ktlint { version.set(libs.versions.ktlint.get()) }
tasks.named("ktlintCheck") { dependsOn(gradle.includedBuild("build-logic").task(":ktlintCheck")) }

subprojects {
    // :sendspin is a verbatim upstream copy: not linted, and its compiler warnings are upstream's to fix.
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
