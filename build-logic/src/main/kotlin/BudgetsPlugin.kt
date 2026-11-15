import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

/**
 * The budgets as build failures: permissions in the merged manifest, the release runtime classpath
 * against docs/dependencies.txt, and the release APK size.
 */
class BudgetsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val allowlistFile = project.rootProject.layout.projectDirectory.file("docs/dependencies.txt")
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val name = variant.name.replaceFirstChar { it.uppercase() }
            project.tasks.register("check${name}Permissions", CheckPermissionsTask::class.java) {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                allowed.set(BASE_PERMISSIONS)
            }
            project.tasks.register("check${name}ApkSize", CheckApkSizeTask::class.java) {
                apkDir.set(variant.artifacts.get(SingleArtifact.APK))
                maxBytes.set(MAX_APK_BYTES)
            }
            project.tasks.register("check${name}Dependencies", CheckDependenciesTask::class.java) {
                resolved.set(modulesOf(variant.runtimeConfiguration))
                allowlist.set(allowlistFile)
                update.set(false)
            }
            project.tasks.register("update${name}DependencyAllowlist", CheckDependenciesTask::class.java) {
                resolved.set(modulesOf(variant.runtimeConfiguration))
                allowlist.set(allowlistFile)
                update.set(true)
            }
        }
        project.tasks.register("checkBudgets") {
            group = "verification"
            description = "Fails when permissions, dependencies or APK size leave their budgets."
            dependsOn("checkReleasePermissions", "checkReleaseApkSize", "checkReleaseDependencies")
        }
        project.tasks.register("updateDependencyAllowlist") {
            dependsOn("updateReleaseDependencyAllowlist")
        }
    }

    private fun modulesOf(config: Configuration): Provider<List<String>> =
        config.incoming.resolutionResult.rootComponent.map { root ->
            val seen = mutableSetOf<String>()
            fun walk(component: ResolvedComponentResult) {
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dep ->
                    val id = dep.selected.id
                    val key = if (id is ModuleComponentIdentifier) "${id.group}:${id.module}" else id.displayName
                    if (seen.add(key)) walk(dep.selected)
                }
            }
            walk(root)
            seen.filterNot { it.startsWith("project ") }.sorted()
        }

    companion object {
        const val MAX_APK_BYTES = 8_000_000L
        val BASE_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.WAKE_LOCK",
            // Downloads, which the owner asked for with a notification: the service that runs them, and its notice.
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.POST_NOTIFICATIONS",
            // Updates installed from the app itself, which the owner asked for: Android's installer needs it.
            "android.permission.REQUEST_INSTALL_PACKAGES",
        )
    }
}

abstract class CheckPermissionsTask : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty

    @get:Input abstract val allowed: SetProperty<String>

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        val used = Regex("""<uses-permission(?:-sdk-23)?\s[^>]*android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSortedSet()
        val extra = used - allowed.get()
        if (extra.isNotEmpty()) {
            throw GradleException("Permissions outside the budget: $extra. See docs/PLAN.md rule 5.")
        }
        logger.lifecycle("Permissions OK: $used")
    }
}

abstract class CheckApkSizeTask : DefaultTask() {
    @get:InputFiles abstract val apkDir: DirectoryProperty

    @get:Input abstract val maxBytes: Property<Long>

    @TaskAction
    fun check() {
        val apks = apkDir.get().asFile.walkTopDown().filter { it.extension == "apk" }.toList()
        if (apks.isEmpty()) throw GradleException("No APK found in ${apkDir.get()}")
        apks.forEach { apk ->
            val size = apk.length()
            val megabytes = "%.2f".format(size / 1_000_000.0)
            logger.lifecycle("APK ${apk.name}: $megabytes MB of ${maxBytes.get() / 1_000_000} MB")
            if (size > maxBytes.get()) {
                throw GradleException("${apk.name} is $size bytes, over the ${maxBytes.get()} byte budget")
            }
        }
    }
}

abstract class CheckDependenciesTask : DefaultTask() {
    @get:Input abstract val resolved: ListProperty<String>

    @get:InputFile abstract val allowlist: RegularFileProperty

    @get:Input abstract val update: Property<Boolean>

    @TaskAction
    fun check() {
        val file = allowlist.get().asFile
        val current = resolved.get().toSortedSet()
        val header = file.readLines().takeWhile { it.startsWith("#") }
        if (update.get()) {
            file.writeText((header + current).joinToString("\n", postfix = "\n"))
            logger.lifecycle("Wrote ${current.size} entries to ${file.name}")
            return
        }
        val allowed = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSortedSet()
        val added = current - allowed
        val removed = allowed - current
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            throw GradleException(
                "Release runtime classpath differs from docs/dependencies.txt.\n" +
                    "Not on the allowlist: $added\nNo longer used: $removed\n" +
                    "If intended, give the reason in the PR and run ./gradlew :app:updateDependencyAllowlist",
            )
        }
        logger.lifecycle("Dependencies OK: ${current.size} modules match the allowlist")
    }
}
