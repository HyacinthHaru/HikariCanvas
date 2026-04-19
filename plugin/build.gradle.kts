plugins {
    `java`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 21
    }

    jar {
        archiveBaseName.set("HikariCanvas")
    }

    runServer {
        minecraftVersion("1.21.11")
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
