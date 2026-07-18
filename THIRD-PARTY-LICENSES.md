# Third-Party Licenses

HikariCanvas itself is licensed under the **MIT License** (see [`LICENSE`](LICENSE)).

This document lists the third-party components redistributed inside the released
plugin jar, and their licenses. All of them are compatible with redistribution
under a permissive-licensed product.

> **PacketEvents is NOT bundled.** PacketEvents is licensed under **GPL-3.0**
> (copyleft), so it is intentionally *not* shipped inside this jar. It is a
> required, separately-installed plugin dependency — server owners install the
> standalone PacketEvents plugin themselves. See
> [`docs/deployment.md`](docs/deployment.md). This keeps the distributed
> HikariCanvas jar free of copyleft code and cleanly MIT-licensed.

---

## Fonts

Bundled in the jar under `/fonts/`. **All fonts are licensed under the
SIL Open Font License, Version 1.1** (<https://openfontlicense.org/>). Each
font's full copyright notice and license text are available at its upstream
source below.

| Font | Author / Foundry | Source |
|---|---|---|
| Source Han Sans SC | Adobe | github.com/adobe-fonts/source-han-sans |
| Source Han Serif SC | Adobe | github.com/adobe-fonts/source-han-serif |
| Ark Pixel | TakWolf | github.com/TakWolf/ark-pixel-font |
| Inter | Rasmus Andersson | github.com/rsms/inter |
| Noto Serif | The Noto Project (Google) | github.com/notofonts |
| JetBrains Mono | JetBrains s.r.o. | github.com/JetBrains/JetBrainsMono |
| Fira Code | The Fira Code Authors | github.com/tonsky/FiraCode |
| Smiley Sans (得意黑) | Atelier Anchor | github.com/atelier-anchor/smiley-sans |
| Ma Shan Zheng (马善政) | Google Fonts | github.com/google/fonts (ofl/mashanzheng) |
| ZCOOL XiaoWei | Google Fonts | github.com/google/fonts (ofl/zcoolxiaowei) |
| ZCOOL KuaiLe | Google Fonts | github.com/google/fonts (ofl/zcoolkuaile) |
| ZCOOL QingKe HuangYou | Google Fonts | github.com/google/fonts (ofl/zcoolqingkehuangyou) |
| LXGW WenKai (霞鹜文楷) | LXGW (lxgw) | github.com/lxgw/LxgwWenKai |
| Comic Neue | Craig Rozynski | github.com/google/fonts (ofl/comicneue) |
| Pacifico | Vernon Adams | github.com/google/fonts (ofl/pacifico) |
| Lobster | Impallari Type | github.com/google/fonts (ofl/lobster) |
| Bangers | Vernon Adams | github.com/google/fonts (ofl/bangers) |
| Shadows Into Light | Kimberly Geswein | github.com/google/fonts (ofl/shadowsintolight) |
| Caveat | Impallari Type | github.com/google/fonts (ofl/caveat) |
| Dancing Script | Impallari Type | github.com/google/fonts (ofl/dancingscript) |
| Overpass | Red Hat, Inc. / Delve Fonts | github.com/google/fonts (ofl/overpass) |
| Bebas Neue | Ryoichi Tsunekawa (Dharma Type) | github.com/google/fonts (ofl/bebasneue) |

> **OFL note:** the SIL OFL 1.1 requires that its license text accompany the
> redistributed fonts. The fonts are downloaded at build time (SHA-256 pinned)
> and merged into the jar; a future build step should also copy each font's
> `OFL.txt` into the jar alongside the fonts for full in-artifact compliance.
> Until then this file provides the attribution and license identification.

---

## Icons

Bundled in the jar under `/icons/` (as vector path JSON).

**Font Awesome Free 6.7.2** — © Fonticons, Inc. (<https://fontawesome.com>)
- Icons: **CC BY 4.0**
- Fonts: **SIL OFL 1.1**
- Code: **MIT**

Source: github.com/FortAwesome/Font-Awesome

---

## Backend libraries

Shaded into the jar under `ac.haru.hikaricanvas.shaded.*` (except SQLite JDBC,
which keeps its original package for JNI native loading).

| Library | Version | License |
|---|---|---|
| Javalin | 7.1.0 | Apache 2.0 |
| Jackson (databind + dataformat-yaml) | 2.22.1 | Apache 2.0 |
| SnakeYAML (via jackson-dataformat-yaml) | — | Apache 2.0 |
| Caffeine | 3.1.8 | Apache 2.0 |
| JDBI (jdbi3-core, jdbi3-sqlite) | 3.52.1 | Apache 2.0 |
| HikariCP | 7.0.2 | Apache 2.0 |
| SQLite JDBC (org.xerial) | 3.53.0.0 | Apache 2.0 |
| Jetty (transitive via Javalin) | — | Apache 2.0 / EPL 2.0 |

---

## Frontend libraries

Bundled in the compiled web editor (jar `/web/`).

| Library | License |
|---|---|
| Vue 3 | MIT |
| Pinia | MIT |
| Konva / vue-konva | MIT |
| Lexical (+ `@lexical/*`) | MIT |
| fontkit | MIT |
| polygon-clipping | MIT |
| fflate | MIT |
| @vueuse/core | MIT |
| lucide-vue-next | ISC |
| Tailwind CSS | MIT |

---

*Full license texts for each component are available at the linked sources.
If you believe an attribution here is incomplete or incorrect, please open an
issue at <https://github.com/HyacinthHaru/HikariCanvas/issues>.*
