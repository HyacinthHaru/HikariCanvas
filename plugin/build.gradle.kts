import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

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

// ---- M4-T1 构建期 palette.json 生成 ----
// 独立 sourceSet 'generator' 隔离构建期工具类，避免 classes → processResources
// → generatePalette → classes 的循环依赖。
//
// 链路：compileGeneratorJava → generatePalette JavaExec
//       → build/generated/palette/palette.json
//       → processResources 作为资源合并 → shadow jar 根路径 palette.json

val generatorSource = sourceSets.create("generator") {
    java {
        setSrcDirs(listOf("src/generator/java"))
    }
    // generator 只需要 paper-api（为了 MapPalette），不需要 main 的 runtime classpath
    compileClasspath += sourceSets["main"].compileClasspath
    runtimeClasspath += output + compileClasspath
}

val generatedPaletteResources = layout.buildDirectory.dir("generated/palette-resources")
val paletteJson = generatedPaletteResources.map { it.file("palette.json") }

val generatePalette = tasks.register<JavaExec>("generatePalette") {
    group = "build"
    description = "导出 Paper MapPalette 全部调色板到 palette.json（构建期一次性）"
    dependsOn(tasks.named("compileGeneratorJava"))
    classpath = generatorSource.runtimeClasspath
    mainClass.set("moe.hikari.canvas.build.PaletteGenerator")
    // 用 argumentProviders 延迟到执行期 resolve Provider；直接传 Provider 给 args()
    // 会把 Provider.toString() 当字符串传进去，导致文件名里出现 "map(map(...))"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(paletteJson.get().asFile.absolutePath)
    })
    outputs.file(paletteJson)
    // 输入指纹：generator 源码 + Paper 版本不变就复用缓存
    inputs.files(generatorSource.allSource)
}

// ---- M4-T3 构建期下载内置字体 ----
// 仓库不打包字体文件（>30 MB）。首次 `./gradlew shadowJar` 时从 GitHub Release 抓到
// build/downloaded-fonts/，SHA-256 校验（M7 polish pin 实际值；M4 留空仅 log）。
// processResources 把 *.ttf / *.otf 合并到 jar 的 /fonts/ classpath 子目录，
// FontRegistry 启动时 getResourceAsStream 读。

data class FontSpec(
    val displayId: String,
    val url: String,
    val destFileName: String,
    val expectedSha256: String,  // 空串 = 不校验，只 log 实际值
    val inZipEntryPattern: String? = null  // 非 null = 下载的是 zip，按模式提取
)

val bundledFonts = listOf(
    FontSpec(
        displayId = "source_han_sans",
        url = "https://github.com/adobe-fonts/source-han-sans/raw/release/OTF/SimplifiedChinese/SourceHanSansSC-Regular.otf",
        destFileName = "SourceHanSansSC-Regular.otf",
        expectedSha256 = "f1d8611151880c6c336aabeac4640ef434fa13cbfbf1ffe82d0a71b2a5637256"
    ),
    FontSpec(
        displayId = "ark_pixel",
        url = "https://github.com/TakWolf/ark-pixel-font/releases/download/2026.02.27/ark-pixel-font-12px-monospaced-ttf-v2026.02.27.zip",
        destFileName = "ark-pixel-12px-monospaced-zh_cn.ttf",
        expectedSha256 = "2fa78b40f74714b0092fa549eb6814b3efec5a729d020254968a270771ba5f75",
        inZipEntryPattern = ".*monospaced-zh_cn\\.ttf"
    )
)

val downloadedFontsDir = layout.buildDirectory.dir("downloaded-fonts")

fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { it.readAllBytes().let(md::update) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val downloadFonts = tasks.register("downloadFonts") {
    group = "build"
    description = "下载内置字体（思源黑体 + Ark Pixel 12px）到 build/downloaded-fonts/"
    outputs.dir(downloadedFontsDir)
    doLast {
        val dir = downloadedFontsDir.get().asFile
        dir.mkdirs()
        for (spec in bundledFonts) {
            val dest = File(dir, spec.destFileName)
            if (dest.exists() && dest.length() > 0 &&
                (spec.expectedSha256.isEmpty() || sha256Hex(dest) == spec.expectedSha256)) {
                logger.info("  [skip] ${spec.destFileName} already present & verified")
                continue
            }
            logger.lifecycle("  [fetch] ${spec.displayId} <- ${spec.url}")
            val tempFile = File(dir, spec.destFileName + ".tmp")
            // GitHub Releases 对大文件时常 Premature EOF；重试 3 次，每次完整重下
            var lastErr: Exception? = null
            val maxAttempts = 3
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                try {
                    val conn = URI(spec.url).toURL().openConnection()
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 120_000
                    conn.getInputStream().use { input ->
                        Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    lastErr = null
                    break
                } catch (e: Exception) {
                    lastErr = e
                    logger.lifecycle("  [retry $attempt/$maxAttempts] ${spec.destFileName}: ${e.message}")
                }
            }
            if (lastErr != null) {
                throw GradleException(
                    "下载 ${spec.destFileName} 失败（$maxAttempts 次重试均异常）。" +
                    "可手动下载 ${spec.url} 放到 ${dir.absolutePath}/${spec.destFileName}" +
                    (spec.inZipEntryPattern?.let { "（zip 需解压，按模式 $it 提取）" } ?: ""),
                    lastErr
                )
            }
            if (spec.inZipEntryPattern != null) {
                // 解压出匹配的条目
                ZipFile(tempFile).use { zip ->
                    val regex = Regex(spec.inZipEntryPattern)
                    val entry = zip.entries().asSequence()
                        .firstOrNull { regex.matches(it.name) || regex.matches(it.name.substringAfterLast('/')) }
                        ?: error("未在 zip 中找到匹配 ${spec.inZipEntryPattern} 的条目；zip=${tempFile.name}")
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                tempFile.delete()
            } else {
                tempFile.renameTo(dest)
            }
            val actual = sha256Hex(dest)
            if (spec.expectedSha256.isEmpty()) {
                logger.lifecycle("  [sha256 未 pin] ${spec.destFileName} = $actual （首次构建；建议填入 build.gradle.kts）")
            } else if (actual != spec.expectedSha256) {
                error("SHA-256 不符：${spec.destFileName} 期望 ${spec.expectedSha256}，实得 $actual")
            }
        }
    }
}

// downloadedFontsDir 里是 *.ttf / *.otf；processResources 从该目录读并放到 jar 的 /fonts/ 下
sourceSets.main {
    resources.srcDir(generatedWebResources)
    resources.srcDir(generatedPaletteResources)
}

tasks.processResources {
    dependsOn(copyWebToResources)
    dependsOn(generatePalette)
    dependsOn(downloadFonts)
    from(downloadedFontsDir) {
        include("*.ttf", "*.otf")
        into("fonts")
    }
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
