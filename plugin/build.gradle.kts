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
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("com.github.retrooper:packetevents-spigot:2.11.2")

    // 持久化（M2-T2）
    implementation("org.xerial:sqlite-jdbc:3.53.0.0")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.jdbi:jdbi3-core:3.52.1")
    implementation("org.jdbi:jdbi3-sqlite:3.52.1")

    // M15 内存上限：wallPreviewCache 等需要 LRU + TTL（替代 ConcurrentHashMap 无界）
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // M4-T11 snapshot 测试台
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // M15 测试基建：MockBukkit（FrameDeployer / wall.lock owner-only 等需要 Bukkit world / entity 设施）
    testImplementation("com.github.seeseemelk:MockBukkit-v1.21:3.123.0")
    // M15 测试基建：JavalinTest（HTTP / WS 端到端测试 — UploadHandler 全场景 / sessionId 鉴权）
    testImplementation("io.javalin:javalin-testtools:7.1.0")
}

tasks.test {
    useJUnitPlatform()
    // 保证 palette.json / fonts 在 test classpath 里就绪
    dependsOn(tasks.processResources)
}

// ---- Gradle ↔ npm 联动 ----
// 把 web/ 子项目的 Vite 产物拷成 Java 资源，让 shadowJar 自动包进去。
// 产出挂在 web/ 前缀下，Javalin 再通过 cfg.staticFiles.add("/web", CLASSPATH) serve。

val webBuildDir = rootProject.layout.projectDirectory.dir("web")
val generatedWebResources = layout.buildDirectory.dir("generated/web-resources")

val installWebDeps = tasks.register<Exec>("installWebDeps") {
    group = "build"
    description = "npm ci in web/ — only runs when node_modules is missing"
    workingDir = webBuildDir.asFile
    // M16 P5.3：npm ci 严格按 package-lock.json 装，可重现性 > 自动升级；
    // package.json 与 lock 不一致直接报错，比 npm install 静默升级更安全。
    commandLine("npm", "ci")
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

// ---- M20-T1 构建期字体 advance 表生成 ----
// 链路：downloadFonts → generateGlyphMetrics（每字体一次 JavaExec）
//       → build/generated/glyph-metrics/{fontId}.metrics.json
//       → processResources 合并到 jar `/fonts/{fontId}.metrics.json` 供后端读
//       → syncFontsToWeb 同步到 web/public/fonts/{fontId}.metrics.json 供前端 fetch
//
// 注意：generator sourceSet 已有，无需新建。

val generatedGlyphMetricsDir = layout.buildDirectory.dir("generated/glyph-metrics")

// Gradle 9 起 project.javaexec { } 在 task action 里不再可用；改用 per-font JavaExec subtask
// 聚合：父任务 generateGlyphMetrics 仅 dependsOn 所有 generateGlyphMetrics_<fontId>。
val generateGlyphMetricsTasks = bundledFonts.map { spec ->
    tasks.register<JavaExec>("generateGlyphMetrics_${spec.displayId}") {
        group = "build"
        description = "生成 ${spec.displayId} 的 BMP advance 查找表 JSON"
        dependsOn(downloadFonts)
        dependsOn(tasks.named("compileGeneratorJava"))

        val fontFile = downloadedFontsDir.map { it.file(spec.destFileName) }
        val outFile = generatedGlyphMetricsDir.map { it.file("${spec.displayId}.metrics.json") }

        classpath = generatorSource.runtimeClasspath
        mainClass.set("moe.hikari.canvas.build.GlyphMetricsGenerator")
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                fontFile.get().asFile.absolutePath,
                spec.displayId,
                outFile.get().asFile.absolutePath
            )
        })

        // 输入指纹：generator 源码 + 字体文件本身；任一变则重跑
        inputs.files(generatorSource.allSource)
        inputs.file(fontFile)
        outputs.file(outFile)
    }
}

val generateGlyphMetrics = tasks.register("generateGlyphMetrics") {
    group = "build"
    description = "对所有内置字体生成 BMP advance 查找表 JSON（聚合任务）"
    dependsOn(generateGlyphMetricsTasks)
}

// M5-C1：把已下载的字体同步到 web/public/fonts/，Vite 通过 @font-face 加载到浏览器
// Canvas 2D / TextLayout 需要与 Java Graphics2D 使用完全相同的 TTF/OTF（rendering.md §2.1）
// M20-T1：同步追加 *.metrics.json（前端运行期 fetch）
val webFontsDir = rootProject.layout.projectDirectory.dir("web/public/fonts")
val syncFontsToWeb = tasks.register<Copy>("syncFontsToWeb") {
    group = "build"
    description = "把 build/downloaded-fonts/*.ttf|otf + glyph metrics JSON 拷到 web/public/fonts/"
    dependsOn(downloadFonts)
    dependsOn(generateGlyphMetrics)
    from(downloadedFontsDir) {
        include("*.ttf", "*.otf")
    }
    from(generatedGlyphMetricsDir) {
        include("*.metrics.json")
    }
    into(webFontsDir)
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
    dependsOn(generateGlyphMetrics)
    dependsOn(syncFontsToWeb)
    from(downloadedFontsDir) {
        include("*.ttf", "*.otf")
        into("fonts")
    }
    // M20-T1：metrics.json 进 jar /fonts/，后端 FontRegistry 后续 phase 用 getResourceAsStream 读
    from(generatedGlyphMetricsDir) {
        include("*.metrics.json")
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
        // M16 P5.1：把所有 runtime 内嵌 lib relocate 到 moe.hikari.canvas.shaded.*，防止
        // 与生产服其它插件（多半也带 jackson 等）发生类加载冲突。
        //
        // 注意事项：
        // - org.sqlite 含 JNI native lib（路径硬编码），relocate 会导致 native load 失败 → 不动
        // - PacketEvents 是 plugin-loader 模式（compileOnly），不进 shadow jar → 无需 relocate
        // - mergeServiceFiles 必须保留：jackson modules / jdbi plugins / jetty 都靠
        //   META-INF/services 走 ServiceLoader 注册
        relocate("com.fasterxml.jackson", "moe.hikari.canvas.shaded.jackson")
        relocate("com.github.benmanes.caffeine", "moe.hikari.canvas.shaded.caffeine")
        relocate("org.jdbi", "moe.hikari.canvas.shaded.jdbi")
        relocate("com.zaxxer.hikari", "moe.hikari.canvas.shaded.hikari")
        relocate("io.javalin", "moe.hikari.canvas.shaded.javalin")
        relocate("org.eclipse.jetty", "moe.hikari.canvas.shaded.jetty")
        // jackson-dataformat-yaml 间接依赖 SnakeYAML；同步 relocate 避免半 shade
        relocate("org.yaml.snakeyaml", "moe.hikari.canvas.shaded.snakeyaml")
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
