plugins {
    `java`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.4.1"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/") { name = "codemc-releases" }
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    implementation("io.javalin:javalin:7.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.github.retrooper:packetevents-spigot:2.11.2")

    // 持久化（M2-T2）
    implementation("org.xerial:sqlite-jdbc:3.53.0.0")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.jdbi:jdbi3-core:3.52.1")
    implementation("org.jdbi:jdbi3-sqlite:3.52.1")
}

// ---- Gradle ↔ npm 联动 ----
// 把 web/ 子项目的 Vite 产物拷成 Java 资源，让 shadowJar 自动包进去。
// 产出挂在 web/ 前缀下，Javalin 再通过 cfg.staticFiles.add("/web", CLASSPATH) serve。

val webBuildDir = rootProject.layout.projectDirectory.dir("web")
val generatedWebResources = layout.buildDirectory.dir("generated/web-resources")

val installWebDeps = tasks.register<Exec>("installWebDeps") {
    group = "build"
    description = "npm install in web/ — only runs when node_modules is missing"
    workingDir = webBuildDir.asFile
    commandLine("npm", "install")
    onlyIf { !webBuildDir.dir("node_modules").asFile.exists() }
    outputs.dir(webBuildDir.dir("node_modules"))
}

val buildWeb = tasks.register<Exec>("buildWeb") {
    dependsOn(installWebDeps)
    group = "build"
    description = "Runs `npm run build` in web/"
    workingDir = webBuildDir.asFile
    commandLine("npm", "run", "build")
    inputs.file(webBuildDir.file("package.json"))
    inputs.file(webBuildDir.file("package-lock.json"))
    inputs.file(webBuildDir.file("vite.config.ts"))
    inputs.file(webBuildDir.file("tsconfig.json"))
    inputs.file(webBuildDir.file("index.html"))
    inputs.dir(webBuildDir.dir("src"))
    outputs.dir(webBuildDir.dir("dist"))
}

val copyWebToResources = tasks.register<Copy>("copyWebToResources") {
    dependsOn(buildWeb)
    from(webBuildDir.dir("dist"))
    into(generatedWebResources.map { it.dir("web") })
}

sourceSets.main {
    resources.srcDir(generatedWebResources)
}

tasks.processResources {
    dependsOn(copyWebToResources)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 21
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("HikariCanvas")
        archiveClassifier.set("")
        mergeServiceFiles()
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("1.21.11")
        pluginJars.from(shadowJar.flatMap { it.archiveFile })
        doFirst {
            val eula = project.file("run/eula.txt")
            eula.parentFile.mkdirs()
            if (!eula.exists() || !eula.readText().contains("eula=true")) {
                eula.writeText("eula=true\n")
                logger.lifecycle("Wrote eula=true to $eula (accepting Mojang EULA for local dev server)")
            }
        }
    }
}
