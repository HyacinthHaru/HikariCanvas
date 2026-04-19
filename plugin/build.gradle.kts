plugins {
    `java`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
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
}
