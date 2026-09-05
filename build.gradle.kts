plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel") {}
}

subprojects {
    if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
        tasks.register("prepareKotlinBuildScriptModel") {}
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.add("-P")
                    freeCompilerArgs.add(
                        "plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics",
                    )
                }
            }
        }
    }
}

// Force Gradle to fetch fresh SNAPSHOTs instead of turning off the build cache
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            cacheChangingModulesFor(0, "seconds")

            // SimpMusic stream-resolution port (2026-09-05), copied verbatim from
            // SimpMusic's root build.gradle.kts: PipePipe and Brave both depend on
            // com.github.TeamNewPipe:nanojson with different commit hashes. Gradle's
            // default resolver picks PipePipe's older 1d9e1aea... commit which lacks
            // JsonArray.streamAsJsonObjects(), causing NoSuchMethodError when Brave's
            // fallback runs at runtime. Force the latest upstream commit (newer than
            // both libs ship) across every module so the merged APK/JAR carries a
            // nanojson with the API both extractors expect.
            force("com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166")
        }
    }
}
