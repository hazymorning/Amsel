import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/**
 * Resolves android-all through Gradle, so CI's Gradle cache keeps it: Robolectric's own download goes
 * to ~/.m2, which no CI cache holds, more than 200 MB for every job that runs Robolectric.
 */
class RobolectricPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val android = project.configurations.create("robolectricAndroid") {
            isCanBeConsumed = false
            isTransitive = false
        }
        project.dependencies.addProvider(android.name, libs.findLibrary("robolectric-android-all").get())
        project.tasks.withType(Test::class.java).configureEach {
            jvmArgumentProviders.add(OfflineAndroid(android))
        }
    }
}

/** Offline, Robolectric looks the jar up by its file name in robolectric.dependency.dir. */
class OfflineAndroid(@get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) val jars: FileCollection) :
    CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${jars.singleFile.parentFile}",
    )
}
