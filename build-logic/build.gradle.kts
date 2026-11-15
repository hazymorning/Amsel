plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

ktlint { version.set(libs.versions.ktlint.get()) }

dependencies {
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
}

gradlePlugin {
    plugins {
        register("budgets") {
            id = "amsel.budgets"
            implementationClass = "BudgetsPlugin"
        }
    }
}
