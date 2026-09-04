# AGENTS.md

PixelPlayer 是 Android 音乐播放器（100% Kotlin，Jetpack Compose + Material 3）。本仓库为 PixelPlayerHQ/PixelPlayer 的本地 fork（`pisces312/PixelPlayer`，rootProject 名 `PixelPlay`）。Gradle 根模块：`:app`（手机端）、`:shared`（纯 DTO/序列化）、`:wear`（Wear OS）、`:baselineprofile`（基线 profile）。

## 构建环境

- Gradle 9.5.1（wrapper，腾讯云镜像）、AGP 9.2.1、Kotlin 2.4.0、KSP 2.3.9、JDK 21（JBR）；compileSdk = targetSdk = 37，minSdk = 30。
- `gradle.properties` 已开启 `android.builtInKotlin=true`、`android.newDsl=true`（AGP 9 新 DSL）、configuration-cache、parallel，JVM 堆 `-Xmx4096m -XX:MaxMetaspaceSize=1024m`（约 3GB 预算，勿再加 worker/堆）。
- 依赖仓库阿里云镜像优先（`settings.gradle.kts`）；Gradle 发行版走腾讯云镜像（`gradle/wrapper/gradle-wrapper.properties`）。
- 依赖版本单一来源：`gradle/libs.versions.toml`。根 `build.gradle.kts` 强制 `kotlin-metadata-jvm:2.4.0` / `kotlinx-metadata-jvm:0.9.0`，勿随意改动。

## 常用命令（Windows/PowerShell，仓库根目录执行）

```powershell
# Debug 手机端 APK（默认仅 arm64-v8a 拆分）
.\gradlew.bat :app:assembleDebug "-Ppixelplay.enableAbiSplits=true"

# Release（需先设置签名环境变量，见「关键约束」）
.\gradlew.bat :app:assembleRelease "-Ppixelplay.enableAbiSplits=true"

# Wear OS Debug
.\gradlew.bat :wear:assembleDebug

# 单元测试（JUnit 5 Platform，全局 useJUnitPlatform）
.\gradlew.bat :app:testDebugUnitTest

# 跑单个测试（全限定类名）
.\gradlew.bat :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.xxx.Klass.method"

# 单元测试一键跑（推荐）：跑完自动打印摘要，并区分「基线失败」与「新增回归」
.\run-tests.bat                        # 全部
.\run-tests.bat ImportedHistory        # 只跑匹配 *ImportedHistory* 的测试
.\run-tests.bat --summary-only         # 只重打摘要，不执行 gradle

# Lint（release 构建已关闭 lint 检查，用 debug 变体）
.\gradlew.bat :app:lintDebug

# 基线 profile 生成（需连接真机/模拟器，benchmark 变体）
.\gradlew.bat :app:generateBaselineProfile

# Compose 编译器稳定性报告（可选）
.\gradlew.bat :app:assembleDebug "-Ppixelplay.enableComposeCompilerReports=true"
```

本地构建常加 `--no-daemon` 省内存（见 `docs/build-config.md`）。CI（`.github/workflows`）在 Ubuntu + JDK 21 下执行 `gradle :app:assembleDebug|assembleRelease -Ppixelplay.enableAbiSplits=true`，改动构建逻辑需保证 CI 一致。

## 模块与架构

- `app`：手机主应用，applicationId `com.theveloper.pixelplay`（debug 变体加 `.debug` 后缀）。MVVM + StateFlow/SharedFlow；Hilt DI；Room（schema 在 `app/schemas`，迁移需同步导出并入库）；Media3 ExoPlayer + FFmpeg；Retrofit/OkHttp + Ktor（内置 HTTP 流媒体服务）；TagLib/JAudioTagger 元数据；Glance 桌面小部件；WorkManager；DataStore。
- `shared`：纯 DTO + kotlinx.serialization，minSdk 必须与 app 一致（30），不可引入平台 API。
- `wear`：Wear OS 端，独立 Room 存储，Horologist 媒体组件。
- `baselineprofile`：目标 `:app`，连接设备运行后产出 `app/src/release/generated/baselineProfiles/*`。
- 源码分层 `app/src/main/java/com/theveloper/pixelplay/{data,di,presentation,ui,utils}`；`data/` 含本地 MediaStore + 多远程源（Jellyfin/Navidrome/网易/QQ 音乐/Telegram/Deezer 封面）与 AI provider（Gemini、任意 OpenAI 兼容端点、火山引擎）。

## 关键约束 / 易踩坑

- **签名**：release signingConfig 从环境变量 `KEY_STORE / KEY_STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD` 读取；`keystore.properties`、`local.properties` 均被 gitignore，勿提交真实密钥。
- **Telegram 凭据**：`TELEGRAM_API_ID / TELEGRAM_API_HASH` 从 `local.properties` 读取（`app/build.gradle.kts` 内含回退默认值）；勿把真实凭据写进代码或提交。
- **ABI 拆分**：默认 `pixelplay.enableAbiSplits=true` 时仅构建 `arm64-v8a`（本 fork 定制，区别于上游 arm64+armeabi-v7a），不产出 universal APK。
- **R8 混淆**：release 开启 minify + shrinkResources + `r8.strictFullModeForKeepRules`；keep 规则集中在 `app/proguard-rules.pro`（TagLib、JAudioTagger、FFmpeg 解码、数据模型、Netty、Ktor CIO、TDLib、Kuromoji/Pinyin4J、Glance ActionCallback 等）。改动序列化/反射类时需同步补 keep 规则。
- **Compose 稳定性**：`app/compose_stability.conf` 经 `stabilityConfigurationPath` 注入编译器；StrongSkipping 默认开启。
- **Room schema**：KSP 参数 `room.schemaLocation=$projectDir/schemas`，androidTest 资源也引用该目录；改实体/迁移后 schema JSON 需一并提交。
- **版本号**：`APP_VERSION_NAME / APP_VERSION_CODE` 在 `gradle.properties` 维护；`CHANGELOG.md` 遵循 Keep a Changelog + 语义化版本。
- **许可**：2026-05-12 后专有，此前贡献保持 MIT；`copyThirdPartyNotices` 任务将 `THIRD_PARTY_NOTICES.md` 注入生成资源，增删依赖时保持其准确。
- **debug 图标**：`app/src/debug/res/` 的 DBG 角标图标由仓库根目录 `gen_debug_icons.py` 生成，改动图标后重跑该脚本。

## 测试与验证

- 单元测试在 `app/src/test`（JUnit 5 + MockK + Turbine + Truth）；平台已全局 `useJUnitPlatform()`（`app/build.gradle.kts`）。
- 仪器测试在 `app/src/androidTest`（Room、WorkManager、Compose UI、宏基准）。
- **本地一键脚本**：`run-tests.bat`（调 `test_summary.py`）跑 JVM 单测并在结尾打印摘要，把失败分成「基线失败」与「新增回归」，避免新回归淹没在既有红灯里。只有全量跑（结果目录 ≥20 个类）才做基线过期检查，过滤跑会自动跳过。
- **既有失败基线**：`test_summary.py` 的 `BASELINE_FAILING_CLASSES` 记录在 master 上就已失败的 6 个测试类，均非本 fork 引入。修好其中任一个后，脚本会提示更新该集合；新增失败一律归到「NEW failures」并令脚本退出码非 0。
- **JVM 单测边界**：工程未引入 Robolectric 且开了 `unitTests.isReturnDefaultValues`，Android framework 类在 JVM 下被打桩——例如 `AtomicFile.startWrite()` 恒返回 `null`，`PlaybackStatsRepository` 的文件 I/O 写入会被静默跳过却仍返回 `true`（假成功）。涉及 `AtomicFile`/`Context` 真实文件读写的逻辑**无法在 JVM 单测验证**，只能靠真机或 `AppLogCollector` 导出的日志回读校验。
- lint：`checkReleaseBuilds=false`，正式检查跑 debug 变体。
- 改动构建/产物后按 `docs/build-config.md` 与 CI 工作流交叉核对，交付前回读校验。
