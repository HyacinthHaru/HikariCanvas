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

// 0.9.5 多版本支持：编译目标可参数化，默认 1.21.11 / Java 21（生产 jar）。
// CI 双版本编译守卫用 `-PpaperApi=26.1.2-R0.1-SNAPSHOT -PjavaVer=25` 对 26.x API 编译同一份源码，
// 提前抓「用了 26.x 已移除 API」的回归；本地也可 `./gradlew runServer -PpaperApi=... -PjavaVer=25` 起 26.x dev server。
// paperApi = paperweight dev bundle 坐标（1.21.11 用 `1.21.11-R0.1-SNAPSHOT`；26.x 新版本号用
// `26.1.2.build.+` 追最新 stable build）。mcVersion 单列（runServer 用，两种格式无法统一推导）。
val paperApi: String = providers.gradleProperty("paperApi").getOrElse("1.21.11-R0.1-SNAPSHOT")
val mcVersion: String = providers.gradleProperty("mcVersion").getOrElse("1.21.11")
val javaVer: Int = providers.gradleProperty("javaVer").map(String::toInt).getOrElse(21)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVer)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/") { name = "codemc-releases" }
}

dependencies {
    paperweight.paperDevBundle(paperApi)

    implementation("io.javalin:javalin:7.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    // PacketEvents 是 GPL-3.0 —— compileOnly 不打包（否则组合 jar 会被 GPL 传染，冲突本项目 MIT）。
    // 服主单独装 PacketEvents 插件（paper-plugin.yml 声明为必装依赖）。
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")

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
    mainClass.set("ac.haru.hikaricanvas.build.PaletteGenerator")
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
    ),
    // M21：6 个新内置字体（全 SIL OFL 1.1）
    FontSpec(
        displayId = "source_han_serif",
        url = "https://github.com/adobe-fonts/source-han-serif/raw/release/OTF/SimplifiedChinese/SourceHanSerifSC-Regular.otf",
        destFileName = "SourceHanSerifSC-Regular.otf",
        expectedSha256 = "78aa7a328fd974df2d688c8a9fd74a33d8334dfa84ab24d9d11efb2ffc464117"
    ),
    FontSpec(
        displayId = "jetbrains_mono",
        url = "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Regular.ttf",
        destFileName = "JetBrainsMono-Regular.ttf",
        expectedSha256 = "e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1"
    ),
    FontSpec(
        displayId = "fira_code",
        url = "https://github.com/tonsky/FiraCode/releases/download/6.2/Fira_Code_v6.2.zip",
        destFileName = "FiraCode-Regular.ttf",
        expectedSha256 = "5992ab9640e2df491b2f609467b1de60e8bc39b2c28db184342a0592d98f6117",
        inZipEntryPattern = "ttf/FiraCode-Regular\\.ttf"
    ),
    FontSpec(
        displayId = "inter",
        url = "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip",
        destFileName = "Inter-Regular.otf",
        expectedSha256 = "d4f2b9e148059a15f014cb0f0b8fea8cd11bfa447dd483bedf1b0adc0e2ba799",
        inZipEntryPattern = "extras/otf/Inter-Regular\\.otf"
    ),
    FontSpec(
        displayId = "noto_serif",
        url = "https://github.com/notofonts/notofonts.github.io/raw/main/fonts/NotoSerif/hinted/ttf/NotoSerif-Regular.ttf",
        destFileName = "NotoSerif-Regular.ttf",
        expectedSha256 = "19e72cd8d595fae5bd74a5206f5d938512e1183d4fed7abb1ec1be1d7efa5f88"
    ),
    // 注：source_han_mono SC 单文件不存在（adobe-fonts/source-han-mono release 只发 122MB ttc 多语言合包），
    // 122MB 超过整个 shadow jar 现尺寸 2 倍，本期跳过；未来需中文等宽可走外部字体目录。
    // M22：13 个艺术 / 装饰字体（全 SIL OFL 1.1）
    // 中文艺术 6
    FontSpec(
        displayId = "smiley_sans",
        url = "https://github.com/atelier-anchor/smiley-sans/releases/download/v2.0.0/smiley-sans-v2.0.0.zip",
        destFileName = "SmileySans-Oblique.otf",
        expectedSha256 = "f3ea574697a37b63fff393fa61c781766d2e99daeeb8b6ec7c46d2421b768cb8",
        inZipEntryPattern = "SmileySans-Oblique\\.otf"
    ),
    FontSpec(
        displayId = "ma_shan_zheng",
        url = "https://github.com/google/fonts/raw/main/ofl/mashanzheng/MaShanZheng-Regular.ttf",
        destFileName = "MaShanZheng-Regular.ttf",
        expectedSha256 = "b844c59bf20bf530e41c20d6ff12b383b23a2e553b9b68cc89f070869213155d"
    ),
    FontSpec(
        displayId = "zcool_xiaowei",
        url = "https://github.com/google/fonts/raw/main/ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf",
        destFileName = "ZCOOLXiaoWei-Regular.ttf",
        expectedSha256 = "a42b620140f493db42f741351dfbf343c0936d58588ee8004b8b2a218d997ff1"
    ),
    FontSpec(
        displayId = "zcool_kuaile",
        url = "https://github.com/google/fonts/raw/main/ofl/zcoolkuaile/ZCOOLKuaiLe-Regular.ttf",
        destFileName = "ZCOOLKuaiLe-Regular.ttf",
        expectedSha256 = "812a6fc1fe54b6d73a419245c32dfeba8aa33104d5be90d1cf6af082007cb71d"
    ),
    FontSpec(
        displayId = "zcool_qingkehuangyou",
        url = "https://github.com/google/fonts/raw/main/ofl/zcoolqingkehuangyou/ZCOOLQingKeHuangYou-Regular.ttf",
        destFileName = "ZCOOLQingKeHuangYou-Regular.ttf",
        expectedSha256 = "54f0c0df4308cd74cd0f2fd3494ae054dbc4a1fd6fa7d71f4807eb4cdd8b4136"
    ),
    FontSpec(
        displayId = "lxgw_wenkai",
        url = "https://github.com/lxgw/LxgwWenKai/releases/download/v1.510/LXGWWenKai-Regular.ttf",
        destFileName = "LXGWWenKai-Regular.ttf",
        expectedSha256 = "ea47ec17d0f3d0ed1e6d9c51d6146402d4d1e2f0ff397a90765aaaa0ddd382fb"
    ),
    // 西文装饰 7（permanent_marker 因 Apache License 跳过，用 OFL shadows_into_light 替代马克笔位）
    FontSpec(
        displayId = "comic_neue",
        url = "https://github.com/google/fonts/raw/main/ofl/comicneue/ComicNeue-Regular.ttf",
        destFileName = "ComicNeue-Regular.ttf",
        expectedSha256 = "a0ee5a37c8b27c4db0700137d928598b1e23b0089e1546a8961909176b779360"
    ),
    FontSpec(
        displayId = "pacifico",
        url = "https://github.com/google/fonts/raw/main/ofl/pacifico/Pacifico-Regular.ttf",
        destFileName = "Pacifico-Regular.ttf",
        expectedSha256 = "5b6c0d5334a7bf77dea52b975c5a0c408878c0f7115ed5b6fb151f634b7bf701"
    ),
    FontSpec(
        displayId = "lobster",
        url = "https://github.com/google/fonts/raw/main/ofl/lobster/Lobster-Regular.ttf",
        destFileName = "Lobster-Regular.ttf",
        expectedSha256 = "d6568e697fd50cedc0be04d8aae4127fe95add607e7bff954ca88604be80c205"
    ),
    FontSpec(
        displayId = "bangers",
        url = "https://github.com/google/fonts/raw/main/ofl/bangers/Bangers-Regular.ttf",
        destFileName = "Bangers-Regular.ttf",
        expectedSha256 = "4160a7311de9342674cce9160cde9fcbb30f48190397d86ff1b70b455af65824"
    ),
    FontSpec(
        displayId = "shadows_into_light",
        url = "https://github.com/google/fonts/raw/main/ofl/shadowsintolight/ShadowsIntoLight.ttf",
        destFileName = "ShadowsIntoLight.ttf",
        expectedSha256 = "1347863151acdc00fa281daaba1a3543dbce5870b55f9cf7479a15bb84007681"
    ),
    // caveat / dancing_script 仅有 variable font（google/fonts 无 static 目录）；AWT + 浏览器 Canvas
    // 取 default instance（wght=400）双端一致，可直接用。文件名带 [wght] 方括号，URL 编码为 %5Bwght%5D。
    FontSpec(
        displayId = "caveat",
        url = "https://github.com/google/fonts/raw/main/ofl/caveat/Caveat%5Bwght%5D.ttf",
        destFileName = "Caveat-Variable.ttf",
        expectedSha256 = "0bdb6b660482d31531b3945849fba5916b3ef8695da7024a9e6b9ee3c4157988"
    ),
    FontSpec(
        displayId = "dancing_script",
        url = "https://github.com/google/fonts/raw/main/ofl/dancingscript/DancingScript%5Bwght%5D.ttf",
        destFileName = "DancingScript-Variable.ttf",
        expectedSha256 = "21808625578fe8d8cd10cb684be546dca077b27cd03a53a2f1ec11dc743c924c"
    ),
    // M25：用户请求 FHWA Series（美国路牌字体）+ Bahnschrift（Microsoft 专有）的 OFL 替代。
    // Overpass：Red Hat 出品的 FHWA Series B / D 风格替代（variable wght；default 400 双端一致）。
    // Bebas Neue：经典 DIN / Bahnschrift Condensed 风格替代（纯大写英文 condensed sans）。
    // 都来自 google/fonts，全 SIL OFL 1.1。
    FontSpec(
        displayId = "overpass",
        url = "https://github.com/google/fonts/raw/main/ofl/overpass/Overpass%5Bwght%5D.ttf",
        destFileName = "Overpass-Variable.ttf",
        expectedSha256 = "970717df17a7f9911dee45f60695d05bfa9d745fa0a11fc5c348371fa21f0073"
    ),
    FontSpec(
        displayId = "bebas_neue",
        url = "https://github.com/google/fonts/raw/main/ofl/bebasneue/BebasNeue-Regular.ttf",
        destFileName = "BebasNeue-Regular.ttf",
        expectedSha256 = "08e4623805102d819f58601e46e345648846075e363b2ceb23313c2d1c83ec73"
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
        mainClass.set("ac.haru.hikaricanvas.build.GlyphMetricsGenerator")
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

// ---- M26-T1 构建期 Font Awesome Free 矢量图标库下载 + JSON 生成 ----
// 链路：downloadIcons（curl FA zip + SHA-256 校验）
//       → generateIconLibrary JavaExec（IconLibraryGenerator 解 zip 转 JSON）
//       → build/generated/icon-resources/{fa-solid,fa-regular,fa-brands}.icons.json
//       → processResources 进 jar /icons/ 供 IconRegistry 启动期读
//
// 决策：M26 Phase 1 只内置 Font Awesome Free 6.7.2（CC BY 4.0；~2000 icons / pack 总
// 计 ~800KB-1.5MB JSON）；Material Symbols 留 M27 单独 pipeline。IconRegistry 已为
// material/ 命名空间留 hook。

data class IconSpec(
    val displayId: String,
    val url: String,
    val destFileName: String,
    val expectedSha256: String
)

val faIconsSpec = IconSpec(
    displayId = "fontawesome_free",
    // GitHub release zip，13MB；CI 拉得动
    url = "https://github.com/FortAwesome/Font-Awesome/releases/download/6.7.2/fontawesome-free-6.7.2-web.zip",
    destFileName = "fontawesome-free-6.7.2-web.zip",
    // SHA-256 pin（首次构建实测）。GitHub release asset 不变，签名稳定
    expectedSha256 = "ecdaaa6d347cd7da82c66054770995e97f3d066a57e8d58ac9c517f0f77561fb"
)

val downloadedIconsDir = layout.buildDirectory.dir("downloaded-icons")
val generatedIconResources = layout.buildDirectory.dir("generated/icon-resources")

val downloadIcons = tasks.register("downloadIcons") {
    group = "build"
    description = "下载 Font Awesome Free 6.7.2 SVG zip 到 build/downloaded-icons/"
    val dirProv = downloadedIconsDir
    outputs.dir(dirProv)
    doLast {
        val dir = dirProv.get().asFile
        dir.mkdirs()
        val spec = faIconsSpec
        val dest = File(dir, spec.destFileName)
        if (dest.exists() && dest.length() > 0 &&
            (spec.expectedSha256.isEmpty() || sha256Hex(dest) == spec.expectedSha256)) {
            logger.info("  [skip] ${spec.destFileName} already present & verified")
            return@doLast
        }
        logger.lifecycle("  [fetch] ${spec.displayId} <- ${spec.url}")
        val tempFile = File(dir, spec.destFileName + ".tmp")
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
                "可手动下载 ${spec.url} 放到 ${dir.absolutePath}/${spec.destFileName}",
                lastErr
            )
        }
        tempFile.renameTo(dest)
        val actual = sha256Hex(dest)
        if (spec.expectedSha256.isEmpty()) {
            logger.lifecycle("  [sha256 未 pin] ${spec.destFileName} = $actual （首次构建；建议填入 build.gradle.kts）")
        } else if (actual != spec.expectedSha256) {
            error("SHA-256 不符：${spec.destFileName} 期望 ${spec.expectedSha256}，实得 $actual")
        }
    }
}

val generateIconLibrary = tasks.register<JavaExec>("generateIconLibrary") {
    group = "build"
    description = "解 Font Awesome zip 并生成 fa-solid/regular/brands.icons.json"
    dependsOn(downloadIcons)
    dependsOn(tasks.named("compileGeneratorJava"))

    val zipFile = downloadedIconsDir.map { it.file(faIconsSpec.destFileName) }
    val outDir = generatedIconResources

    classpath = generatorSource.runtimeClasspath
    mainClass.set("ac.haru.hikaricanvas.build.IconLibraryGenerator")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            zipFile.get().asFile.absolutePath,
            outDir.get().asFile.absolutePath
        )
    })

    inputs.files(generatorSource.allSource)
    inputs.file(zipFile)
    outputs.dir(outDir)
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
    // M26：FA Free 矢量包入 jar /icons/。IconRegistry.loadBuiltIn 读 classpath。
    dependsOn(generateIconLibrary)
    from(downloadedFontsDir) {
        include("*.ttf", "*.otf")
        into("fonts")
    }
    // M20-T1：metrics.json 进 jar /fonts/，后端 FontRegistry 后续 phase 用 getResourceAsStream 读
    from(generatedGlyphMetricsDir) {
        include("*.metrics.json")
        into("fonts")
    }
    // M26-T1：FA Free 矢量包 JSON 进 jar /icons/，IconRegistry.loadBuiltIn 读 classpath
    from(generatedIconResources) {
        include("*.icons.json")
        into("icons")
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        // 生产 jar 锁 Java 21 字节码（跑 1.21.11 的 Java 21 服 + 向上兼容 26.x 的 Java 25 服）。
        // CI 双版本守卫用 -PjavaVer=25 对 26.x API 编译（26.x paper-api 是 Java 25，无法 --release 21）。
        options.release = javaVer
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("HikariCanvas")
        archiveClassifier.set("")
        // M16 P5.1：把所有 runtime 内嵌 lib relocate 到 ac.haru.hikaricanvas.shaded.*，防止
        // 与生产服其它插件（多半也带 jackson 等）发生类加载冲突。
        //
        // 注意事项：
        // - org.sqlite 含 JNI native lib（路径硬编码），relocate 会导致 native load 失败 → 不动
        // - PacketEvents 是 compileOnly + 必装插件依赖（paper-plugin.yml），不进 shadow jar：
        //   它是 GPL-3.0，打包会污染本项目 MIT 授权；服主单独装 PacketEvents 插件（它自负 init/terminate）
        // - mergeServiceFiles 必须保留：jackson modules / jdbi plugins / jetty 都靠
        //   META-INF/services 走 ServiceLoader 注册
        relocate("com.fasterxml.jackson", "ac.haru.hikaricanvas.shaded.jackson")
        relocate("com.github.benmanes.caffeine", "ac.haru.hikaricanvas.shaded.caffeine")
        relocate("org.jdbi", "ac.haru.hikaricanvas.shaded.jdbi")
        relocate("com.zaxxer.hikari", "ac.haru.hikaricanvas.shaded.hikari")
        relocate("io.javalin", "ac.haru.hikaricanvas.shaded.javalin")
        relocate("org.eclipse.jetty", "ac.haru.hikaricanvas.shaded.jetty")
        // jackson-dataformat-yaml 间接依赖 SnakeYAML；同步 relocate 避免半 shade
        relocate("org.yaml.snakeyaml", "ac.haru.hikaricanvas.shaded.snakeyaml")
        // M28-P4：ac.haru.hikaricanvas.api.* 是公开 API 包，外部插件 import 路径不可变。
        // relocate 仅对显式列出的第三方包 (com.fasterxml.jackson 等) 生效，不会自动 prefix-match
        // 项目自身的 ac.haru.hikaricanvas.*——所以 api 包天然安全。新增 relocate 时务必只列第三方包，
        // 不要加 relocate("ac.haru.hikaricanvas", ...)，否则 ServicesManager.load(HikariCanvasAPI.class)
        // 在外部插件侧立即崩。详见 docs/api.md §2。
        mergeServiceFiles()
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(mcVersion)
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
