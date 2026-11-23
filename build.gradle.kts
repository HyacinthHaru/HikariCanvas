import org.gradle.language.jvm.tasks.ProcessResources

val releaseVersion = providers.gradleProperty("releaseVersion")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

allprojects {
    group = "ac.haru"
    version = releaseVersion ?: "0.9.19-SNAPSHOT"

    tasks.withType<ProcessResources>().configureEach {
        val pluginVersion = project.version.toString()
        inputs.property("version", pluginVersion)
        filesMatching("paper-plugin.yml") {
            expand("version" to pluginVersion)
        }
    }
}
