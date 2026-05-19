plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // 仅引用 API 包（在主 plugin jar 里）；编译期可见，运行期由 HikariCanvas 主插件提供。
    // 主 plugin 的 :jar 被 paperweight 禁用 + shadowJar 跑全套构建慢，且 demo 仅需 API 类符号；
    // 直接 compileOnly plugin main sourceSet 的 classes 目录（dependsOn compileJava 让 IDE / build 顺序正确）。
    compileOnly(files(rootProject.layout.projectDirectory.dir("plugin/build/classes/java/main")))
}

tasks.compileJava {
    dependsOn(":plugin:compileJava")
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("DemoTrainPlugin")
}
