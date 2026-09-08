# PixelPlayer 生态调研：历史与相关项目（2026-09-08）

> 调研日期：2026-09-08
> 数据时点：GitHub API 实测，2026-09-08（UTC+8 本地时间）
> 目的：梳理 PixelPlayer 项目历史、PixelPlayerHQ 组织、PixelMusic（及其 DMCA 下架事件）、PixelPlayerOSS 与本地 china-only 分支的关系，供后续分支维护决策参考。
> 说明：本文档属于 china-only 分支的调研文档（专属 commit，不 cherry-pick 到 master）。

## 1. 项目族谱

```
theovilardo/PixelPlayer（作者 Theo Vilardo 原始仓库，曾为主仓库）
        │
        ▼
PixelPlayerHQ（GitHub 组织）
        ├── PixelPlayerHQ/PixelPlayer   ← 当前主项目（6,378★）
        └── PixelPlayerHQ/PixelPlayerOSS ← 官方 FOSS 版（186★，GPL-3.0）

PixelPlayerHQ/PixelPlayer 的衍生：
        ├── pisces312/PixelPlayer       ← 本仓库（本地 china-only fork，公开）
        ├── ianshulyadav/PixelMusic     ← 整库复制改名，2026-06-15 DMCA 下架（已删除）
        │        └── ianshulyadav/PixelMusicApp ← 改名复活（90★，更新中）
        └── Xing1P/PixelMusic           ← 下架前 fork 残留（20★，已停更）
```

## 2. 相关仓库一览（2026-09-08 实测）

| 仓库 | Stars | Forks | 创建 | 最近推送 | 许可 | 状态 |
|---|---|---|---|---|---|---|
| PixelPlayerHQ/PixelPlayer | 6,378 | 508 | 2025-05-20 | 2026-08-31 | 专有（source-available） | 主项目，活跃 |
| PixelPlayerHQ/PixelPlayerOSS | 186 | 17 | 2026-05-30 | 2026-09-05 | GPL-3.0-or-later | 官方 FOSS 版，周更 |
| ianshulyadav/PixelMusicApp | 90 | 9 | 2026-06-26 | 2026-09-06 | README 称专有 / LICENSE 识别为 GPL-3.0 | 改名复活，更新中 |
| Xing1P/PixelMusic | 20 | 15 | 2026-06-12 | 2026-06-11 | 专有声明 | 已停更 |
| ianshulyadav/PixelMusic（原始） | — | — | — | 2026-06-15 被下架 | — | 已删除 |
| pisces312/PixelPlayer（本仓库） | 0 | 0 | — | 2026-09-08 | 专有（继承上游） | 公开 fork，china-only 分支 |

## 3. PixelMusic 与 DMCA 事件始末

- 2026 年 6 月中旬，`ianshulyadav/PixelMusic` 将 PixelPlayer 仓库**整库复制**（含约 3,600+ 条上游 commit 历史），改名 "PixelMusic"，并在 Releases 页面公开分发编译 APK。
- 2026-06-15，PixelPlayer 作者一方（贡献者 + 受 LICENSE 版权人书面授权）向 GitHub 提交 DMCA 通知（存档：`github/dmca/2026/06/2026-06-15-pixelplayer.md`），理由：整库侵权、改名重发、未经授权分发二进制 APK；许可为专有 source-available（仅个人非商业使用、禁止衍生与再分发）。
- 原仓库被 GitHub 下架删除。GitHub 不自动连坐 fork，因此 06-12 提前 fork 的 `Xing1P/PixelMusic` 存活至今（停更）；作者本人 06-26 以 `PixelMusicApp` 之名复活。
- 2026-06-20~22 多家德文科技媒体（caschy 博客、tink.at）报道"Pixel Music 一夜爆红后被 DMCA 下架"，媒体猜测原因是无广告 YouTube Music 流媒体；**实际通知原文写的是侵犯 PixelPlayer 自身版权（整库复制 + APK 分发）**。

### 对本地分支的警示

本仓库（pisces312/PixelPlayer）曾在 GitHub Releases 公开分发 APK（v0.7.5.1 / v0.7.6 / v0.7.7-pisces，均含 `*-release.apk`）。上游 LICENSE 明确：

> Redistribution of this Software or any derivative work, in binary or source form, is strictly prohibited without prior written permission from the author.

**公开分发 APK 与 DMCA 判例的行为同构，属于高风险不合规行为；个人自用安装则完全合规。**详见第 5 节合规评估。**处理记录（2026-09-08）**：三个 Release 的 APK 资产已全部删除，Release 记录与截图保留。

## 4. PixelPlayerOSS vs 本地 china-only 分支

| 维度 | PixelPlayerOSS | pisces312/PixelPlayer（china-only） |
|---|---|---|
| 定位/许可/分发 | 官方 FOSS 版；GPL-3.0-or-later；F-Droid + GitHub Releases 周更 | 个人自用 fork；专有许可；APK 不公开（现状：公开了，见第 3 节） |
| 包名/版本 | com.lostf1sh.pixelplayeross（已改名）；v0.3.x | com.theveloper.pixelplay（原名）；0.7.8-pisces |
| 工程模块 | 仅 app + baselineprofile（shared/wear 已删） | app + shared + wear + baselineprofile 全保留 |
| 远程音乐源 | 仅 Navidrome/Subsonic + Jellyfin；Deezer 封面可选 | Jellyfin / Navidrome / 网易 / QQ 音乐 / Deezer 封面 |
| Telegram | 网络层 + DB 全部移除 | 网络层/UI 移除；三张表 + SourceType.TELEGRAM + DAO 刻意保留（schema 恒 v43，备份互通） |
| Google Drive | 全部移除 | 网络层/UI 移除；GDrive 表保留 |
| kuromoji（日文罗马音） | 移除 | 移除（省 ~12.7MB） |
| AI | Gemini 移除，无 AI 歌单 | 保留 Gemini + OpenAI 兼容 + 火山引擎 |
| Chromecast | 移除 | 保留（play-services-cast-framework） |
| Wear OS | 移除（无 :wear 模块） | 保留（:wear 模块） |
| 特有功能 | MusicBrainz 按需元数据匹配；LRCLIB/Deezer 默认关闭（opt-in） | AI 请求日志、keep-screen-on 偏好；debug 亦开 R8，APK ≈ 57 MiB |
| 构建 | JDK 21 / target 37；disableReleaseSigning 供 F-Droid 验证 | JDK 21 / target 37；仅 arm64；无 flavor |

**共同点**：都删了 Telegram 网络层、kuromoji、GDrive 入口；都无 flavor 维度；都以本地 + 自托管播放为主。

**本质差异**：OSS 是"删到能合规开源分发"（GPL/F-Droid 要求，故连 Wear/Cast/AI/Play 依赖、shared/wear 模块都删，改包名、加 MusicBrainz）；本地分支是"删到自己不用"（保留能力最大化，DB 层故意不动以维持 v43 schema 备份互通）。

## 5. china-only 分支分发合规评估（2026-09-08 结论）

依据：上游 LICENSE 文本（本地 `LICENSE` 与上游一致）+ GitHub DMCA 公开存档 + 上游许可时间线（AGENTS.md：2026-05-12 后专有，此前贡献保持 MIT）。

| 行为 | 合规性 | 说明 |
|---|---|---|
| 个人自用（自己设备安装 APK） | ✅ 合规 | 专有许可明确允许"personal, non-commercial use" |
| GitHub 公开 fork 源码（china-only 分支可见） | ⚠️ 低-中风险 | 平台 fork 惯例，上游容忍 fork（DISCLAIMER 仅声明不给支持）；但许可文本禁止再分发，fork 中混有 2026-05-12 后的专有代码，属灰色地带。保留原名 + forked from + LICENSE 可维持现状 |
| GitHub Releases 公开分发 APK | ⛔ 不合规（高风险）→ 已整改 | LICENSE 明确禁止 binary 再分发；DMCA 判例中"Releases 分发 APK"即被点名。2026-09-08 已删除全部 APK 资产 |

**建议动作（供决策）**：
1. ✅ 已完成（2026-09-08）：删除公开 Release 中的 APK 产物（v0.7.5.1 / v0.7.6 / v0.7.7-pisces 的 APK 资产已全部移除）。
2. 如确需向他人分发：先取得作者书面授权；或走 GPL 的 OSS 路线重建（代价：整个项目须开源为 GPL-3.0）。
3. 保持 fork 的可见署名（不更名、不删 LICENSE、保留 forked from 标记）。
4. 正式法律决策建议咨询律师或直接联系作者确认，以上仅为基于公开材料的评估。

## 6. MusicBrainz 与"基于 OSS 加 AI"结论

- **MusicBrainz**：社区维护的开源音乐元数据数据库（2000 年 Robert Kaye 创立；MetaBrainz 基金会运营；数据 CC0 公有领域）；配套 Picard 标签工具与 AcoustID 声学指纹。OSS 用它做"按需匹配录音/专辑/艺术家标识"，默认零请求、用户主动触发。
- **基于 OSS（GPL-3.0）加 AI**：技术上可行（AI = 客户端代码 + HTTP API 调用，与 GPL 无冲突）。许可边界：
  - 仅个人自用：无义务；
  - 对外分发：整包必须 GPL-3.0-or-later，AI 代码需 GPL 兼容（自写或自己代码再授权）；
  - **不能搬入上游专有 AI 代码**（2026-05-12 后）及本地分支中继承自上游的部分；自己写的（如 AI 请求日志）可 GPL 化搬入；
  - 想保持闭源：不要基于 OSS，继续用本地专有 china-only 分支（已含 Gemini/OpenAI/火山引擎）。

## 7. 来源

- https://github.com/PixelPlayerHQ/PixelPlayer
- https://github.com/PixelPlayerHQ/PixelPlayerOSS
- https://github.com/ianshulyadav/PixelMusicApp
- https://github.com/Xing1P/PixelMusic
- https://github.com/github/dmca/blob/master/2026/06/2026-06-15-pixelplayer.md
- https://www.tink.at/blog/pixel-music-android-music-player-dmca-takedown/
- https://stadt-bremerhaven.de/pixel-music-die-musik-player-fuer-android-audiophile/
- https://musicbrainz.org/
- https://lwn.net/Articles/1066384/
- 本仓库：LICENSE / AGENTS.md / settings.gradle.kts / app/build.gradle.kts / data 目录（本地核对）
