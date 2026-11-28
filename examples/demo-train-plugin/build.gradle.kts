plugins {
    java
}

val paperApi: String = providers.gradleProperty("paperApi").getOrElse("1.21.11-R0.1-SNAPSHOT")
val javaVer: Int = providers.gradleProperty("javaVer").map(String::toInt).getOrElse(21)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVer))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    // 严格只依赖公开 API 变体（HikariCanvas-<version>-api.jar），物理隔离内部实现类
    compileOnly(project(path = ":plugin", configuration = "apiElementsOnly"))
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(javaVer)
}

tasks.jar {
    archiveBaseName.set("DemoTrainPlugin")
}
