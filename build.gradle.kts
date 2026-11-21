import org.gradle.language.jvm.tasks.ProcessResources

allprojects {
    group = "ac.haru"
    version = "0.9.19-SNAPSHOT"

    tasks.withType<ProcessResources>().configureEach {
        val pluginVersion = project.version.toString()
        inputs.property("version", pluginVersion)
        filesMatching("paper-plugin.yml") {
            expand("version" to pluginVersion)
        }
    }
}
