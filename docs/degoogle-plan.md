# PixelPlayer 去 Google 化实施方案

> 状态：**已执行（折中方案）**，2026-09-02
> 目标：断绝 Google 云端上传 + 消除无 GMS 设备的"需要启用谷歌服务"通知
> 最终范围：**Google Drive 删除**、**Android Auto 删除**、**Google Fonts 本地化**；Chromecast / Wear 保留并加 GMS 门禁；Gemini 保留
> 基线版本：0.7.5-beta (versionCode 9)，AGP 9.x / compileSdk 37 / minSdk 30
>
> 决策背景：上游 PixelPlayerHQ 极度活跃（近一年 3269 commits），激进删除 Cast/Wear（分别侵入核心文件 + 最活跃模块）会显著抬高后续 merge 冲突成本。故仅删真·云端上传的 GDrive，Cast/Wear 改为"加 GMS 判断"（消通知 + 保留功能 + merge 友好）。

---

## 一、现状基线（已核实）

### 1.1 好消息：无 Google 全家桶
- 无 Firebase（无 `google-services.json`、无 `google-services` 插件、无 Analytics/Crashlytics）
- 无 Google Play Billing / AdMob / ML Kit

### 1.2 Google 网络端点（全部 6 处，需清零）

| # | 端点 | 位置 | 归属 |
|---|---|---|---|
| 1 | `https://generativelanguage.googleapis.com/v1beta` | `data/ai/provider/GeminiAiClient.kt:26` | Gemini 生成 |
| 2 | `https://generativelanguage.googleapis.com/v1beta/models` | `data/ai/GeminiModelService.kt:51` | Gemini 模型列表 |
| 3 | `https://www.googleapis.com/auth/drive.readonly` | `data/gdrive/GDriveConstants.kt:7` | GDrive OAuth scope |
| 4 | `https://oauth2.googleapis.com/token` | `data/gdrive/GDriveConstants.kt:8` | GDrive 换取 token |
| 5 | `https://www.googleapis.com/drive/v3` | `data/gdrive/GDriveConstants.kt:9` | GDrive REST |
| 6 | `https://www.googleapis.com/oauth2/v2/userinfo` | `data/gdrive/GDriveApiService.kt:186` | GDrive 用户信息 |

### 1.3 GMS 本地绑定（不走网络，但依赖 Play 服务）

| 项 | 位置 | 说明 |
|---|---|---|
| `play-services-cast-framework` 22.3.1 | app | Chromecast；Manifest 注册 `CastOptionsProvider` |
| `play-services-wearable` | app + wear | Wear OS 数据层 |
| `ui-text-google-fonts` | app | Montserrat 经 GMS 字体提供器下载（**也是网络请求**） |
| `googleid` + `credentials-play-services-auth` | app | Google Drive 账号登录 |
| `com.google.android.gms.car.application` | app Manifest:163 | Android Auto |
| `kotlinx-coroutines-play-services` | app + wear | 仅为 GMS Task 的 `.await()`，随上两项退场 |

### 1.4 关键辨析：这两个不是 GMS，但仍走 Google 服务器
- **Google Drive**：裸 OkHttp 调 REST（#3–#6），不依赖 GMS，只依赖 Google 账号 OAuth
- **Gemini**：裸 OkHttp 调 REST（#1–#2），**`libs.generativeai` 这个 SDK 依赖在代码中零 import，是死依赖**

删除 SDK 依赖不会关掉这两个功能的流量，必须删功能或换 provider。

---

## 二、重要发现（影响方案）

1. **AI 层是可插拔的，AI 功能可以完整保留**
   `AiProvider` 枚举除 `GEMINI` 外已有 10 个非 Google provider：DeepSeek / Groq / Mistral / NVIDIA NIM / Kimi / GLM / OpenAI / OpenRouter / Ollama / Custom。
   只需摘掉 `GEMINI` 分支，无需删除 AI 功能。

2. **Montserrat 没有本地字体文件**
   `res/font/montserrat_bold.xml` 是 GMS downloadable font 声明（指向 `com.google.android.gms.fonts`），**不含字形**。去 Google Fonts 必须三选一（见 §4.2）。

3. **`androidx.mediarouter` 在 app 中只被 Cast 使用**
   Cast + Wear 都移除后，mediarouter 在 app 内无其他调用点，可一并移除。
   注意：`AudioOutputCategory` 的 `BuiltIn / Bluetooth / Usb / Wired / Other` 基于 AudioManager，与 MediaRouter 无关，**蓝牙/USB 设备检测不受影响**。

4. **GDrive 已侵入数据库 schema**
   `PixelPlayDatabase` version **42**，含 `GDriveSongEntity` / `GDriveFolderEntity`；`Song.gdriveFileId`；`songs.source_type = 3` 表示 gdrive 来源。删除需要迁移决策（见 §4.4）。

---

## 三、实施阶段

### 阶段 0：依赖清理

**`gradle/libs.versions.toml`** — 移除以下条目：

```
googleGenai = "1.58.0"                          # 未使用
googlePlayServicesCast = "22.3.1"               # 移除
googleid = "1.2.0"                              # 移除
playServicesCastFramework = "22.3.1"            # 移除
playServicesWearable = ...                      # 移除
google-genai                                    # 未使用
google-play-services-cast-framework             # 移除
play-services-wearable                          # 移除
googleid                                        # 移除
credentials-play-services-auth                  # 移除
androidx-ui-text-google-fonts                   # 移除
kotlinx-coroutines-play-services                # 随 GMS 退场
horologist-*                                    # 随 :wear 模块删除
generativeai                                    # 死依赖
```

**`app/build.gradle.kts`** — 删除对应 `implementation(...)` 行（313、314、316、317、319、259、324）
**`wear/build.gradle.kts`** — 随模块整体删除
**`settings.gradle.kts`** — 移除 `include(":wear")`

**保留不动**（Google 持有但纯开源、无网络无遥测）：
`material`、`gson`、`hilt-android`(+compiler)、`ksp`、`dagger-hilt-android` 插件、`accompanist-*`、`protobuf-javalite`

### 阶段 1：Android Auto（改动最小，建议先做）
- `app/src/main/AndroidManifest.xml:163-165` — 删 `com.google.android.gms.car.application` meta-data
- 删 `app/src/main/res/xml/automotive_app_desc.xml`
- `data/service/MusicService.kt:607` — 删 `controllerPackage.startsWith("com.google.android.gms.car")` 分支

### 阶段 2：Google Fonts → 本地字体
改动文件：`ui/theme/Type.kt`
影响 UI：`AiPlaylistSheet`、`BetaInfoBottomSheet`、`ChangelogBottomSheet`、`HomeScreen`、`SetupScreen`、`StatsScreen`（用 `ExpTitleTypography`）、`SongInfoBottomSheet`（用 `MontserratFamily`）

同时清理：
- 删 `app/src/main/res/font/montserrat_bold.xml`（GMS 下载字体声明）
- 删 `app/src/main/res/values/font_certs.xml`
- 删 Manifest `preloaded_fonts` meta-data（250-251 行）与 `res/values/preloaded_fonts.xml`

### 阶段 3：Chromecast 移除

**删除（13 个文件）**
```
data/service/cast/CastAudioMimeUtils.kt
data/service/cast/CastOptionsProvider.kt
data/service/cast/CastRemotePlaybackState.kt
data/service/cast/IsoBmffAudioCodecDetector.kt
data/service/CastSyncCoordinator.kt
data/service/player/CastPlayer.kt
data/service/http/CastSessionSecurity.kt
presentation/components/CastBottomSheet.kt
presentation/components/scoped/CastSheetState.kt
presentation/viewmodel/CastRouteStateHolder.kt
presentation/viewmodel/CastStateHolder.kt
presentation/viewmodel/CastTransferStateHolder.kt
app/src/test/.../data/service/cast/CastRemotePlaybackStateTest.kt   # 测试
```

**修改（清理引用，需逐个人工核对）**
`PlayerViewModel.kt`、`PlaybackStateHolder.kt`、`PlaybackDispatchStateHolder.kt`、`DualPlayerEngine.kt`、`MusicService.kt`、`UnifiedPlayerSheetV2.kt`（及 `UnifiedPlayerSheetLayers/Shared/OverlaysLayer`、`FullPlayerContent`）、`SettingsViewModel.kt`、`SettingsCategoryScreen.kt`（798-805 行 cast 设置项）、`SettingsViewModel.kt`、`DeviceCapabilitiesViewModel.kt`（删 `AudioOutputCategory.Cast`）、`DeviceCapabilitiesScreen.kt:1440`、`UserPreferencesRepository.kt`（删 `DISABLE_CAST_AUTOPLAY` 及对应 flow/setter）

**Manifest**：删 `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`（254-255 行）

### 阶段 4：Wear OS 移除

**删除整个 `:wear` 模块**（`wear/` 目录 + `settings.gradle.kts` 的 `include(":wear")`）

**删除 app 侧 8 个文件**
```
data/service/wear/PhoneDirectWatchTransferCoordinator.kt
data/service/wear/PhoneWatchTransferCancellationStore.kt
data/service/wear/PhoneWatchTransferStateStore.kt
data/service/wear/WatchTransferForegroundService.kt
data/service/wear/WearCommandReceiver.kt
data/service/wear/WearPhoneTransferSender.kt
data/service/wear/WearStatePublisher.kt
data/service/wear/WearThemePaletteFactory.kt
```

**Manifest**：删 `WatchTransferForegroundService`(246)、`WearCommandReceiver`(273) 及其 `com.google.android.gms.wearable.*` intent-filter

### 阶段 5：Google Drive 移除

**删除（8 个文件）**
```
data/gdrive/GDriveApiService.kt
data/gdrive/GDriveConstants.kt
data/gdrive/GDriveRepository.kt
data/gdrive/GDriveStreamProxy.kt
presentation/gdrive/auth/GDriveLoginActivity.kt
presentation/gdrive/auth/GDriveLoginViewModel.kt
presentation/gdrive/dashboard/GDriveDashboardScreen.kt
presentation/gdrive/dashboard/GDriveDashboardViewModel.kt
```

**修改**
- `presentation/viewmodel/AccountsViewModel.kt:27` — 删 `ExternalServiceAccount.GOOGLE_DRIVE`
- `presentation/screens/AccountsScreen.kt:719` — 删对应分支
- `di/AppModule.kt:23,227-228` — 删 `GDriveDao` 提供（若采用彻底删库方案）
- **多语言字符串**：`values*/strings_cloud_services.xml`、`strings_auth.xml`、`strings_settings.xml`（zh-rCN / en / de / es / fr / it / ko / nb / ru / tr / in / ar 全部语种）
- `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml` — 检查是否含 gdrive 数据项
- **Manifest**:96 — 删 `GDriveLoginActivity` 声明

### 阶段 6：Gemini provider 摘除（**AI 功能保留**）

| 文件 | 改动 |
|---|---|
| `data/ai/provider/AiProvider.kt` | 删 `GEMINI` 枚举项；`fromString` 默认值改为 `CUSTOM` |
| `data/ai/provider/AiClientFactory.kt` | 删 `AiProvider.GEMINI -> GeminiAiClient(apiKey)` 分支 |
| `data/ai/provider/AiProviderSupport.kt` | `buildProviderChain` 的 `preferredFallbacks` 中删 `AiProvider.GEMINI` |
| `data/ai/provider/GeminiAiClient.kt` | **删除** |
| `data/ai/GeminiModelService.kt` | **删除** |
| `data/ai/provider/UnifiedModelFilter.kt` | 检查并清理 Gemini 模型过滤逻辑 |
| `data/preferences/AiPreferencesRepository.kt` | 存量用户 provider=GEMINI 的迁移兜底 |
| `presentation/viewmodel/AiStateHolder.kt`、`SettingsCategoryScreen.kt`、`SettingsViewModel.kt` | 删 Gemini 专属 UI 项 |

**可选**：`AiClientFactory` 中 OpenRouter 默认模型为 `google/gemini-2.0-flash-lite-preview-02-05:free` —— 流量走 `openrouter.ai` 而非 Google，严格意义上不算 Google 请求；若要求"零 Google 字样"，可换成非 Gemini 默认模型。

---

## 四、待拍板项

### 4.1 AI 默认 provider 改成谁？
`AiProvider.fromString` 当前 fallback 是 `GEMINI`，必须改。候选：`CUSTOM`（用户自填 URL，最灵活）/ `OPENAI` / `OLLAMA`（本地）。

### 4.2 Montserrat 替代字体？
| 方案 | 做法 | 代价 |
|---|---|---|
| A（推荐） | `MontserratFamily` 改指向本地 `gflex_variable.ttf`（GoogleSansRounded） | 零下载、体积不变；标题字形从 Montserrat 变为 Google Sans Flex，**视觉有变化** |
| B | 下载 Montserrat 7 个字重 TTF 放入 `res/font` | 视觉 100% 保留；APK 体积 +约 1–2MB |
| C | 改指向 `genre_variable.ttf` | 需确认该 variable font 覆盖的字重范围 |

### 4.3 GDrive 数据库残留如何处理？
| 方案 | 做法 | 风险 |
|---|---|---|
| **保守（推荐）** | 只删业务层与 UI；保留 `GDriveSongEntity`/`GDriveFolderEntity`/`Song.gdriveFileId`/`source_type=3` 定义与建表逻辑 | 零迁移风险；残留两张永不再写的空表 + 一个字段 |
| 彻底 | 删实体/Dao/字段，写 v42→v43 迁移 | **坑**：minSdk 30（Android 11）自带 SQLite 3.28，**不支持 `ALTER TABLE ... DROP COLUMN`**；删 `Song.gdriveFileId` 必须建新表+拷贝数据+改名+重建索引，42 个版本的迁移链上加一步高风险操作 |

**建议采用保守方案**，并在迁移里加一句清理存量 gdrive 歌曲（`source_type = 3`）的可选逻辑。

### 4.4 是否需要保留"投屏"替代能力？
移除 GMS Cast 后，若仍想支持投屏，可选 DLNA/UPnP（需引入 `Cling` 或 `jmDNS` 等第三方库）——这是新功能开发，不在本次去化范围内，需另议。

---

## 五、验收清单

- [ ] `./gradlew :app:assembleDebug` 编译通过
- [ ] `./gradlew :app:assembleRelease` 编译通过（含 R8，需检查 proguard 规则是否引用已删类）
- [ ] 全仓 grep 零命中：`com.google.android.gms`、`googleapis.com`、`generativelanguage`、`googleid`
- [ ] 检查 `app/build/outputs/sdk-dependencies/release/sdkDependencies.txt` 无 GMS 条目
- [ ] 检查 `app/build/generated/aboutLibraries/*/aboutlibraries.json` 无 GMS 库
- [ ] 单元测试通过：`./gradlew :app:testDebugUnitTest`
- [ ] 真机验证：无 GMS 环境下冷启动不崩溃，播放/歌词/小部件/AI（非 Gemini provider）正常
- [ ] 可选：抓包确认零 Google 域名请求

---

## 六、执行顺序建议

```
阶段 1（Android Auto）  ←  改动最小，先跑通编译验证链路
  ↓
阶段 2（Google Fonts）  ←  单点改动，验证 UI
  ↓
阶段 6（Gemini）        ←  独立模块，不影响主流程
  ↓
阶段 5（GDrive）        ←  涉及多语言资源，工作量中等
  ↓
阶段 3（Cast）          ←  侵入 PlayerViewModel，工作量最大
  ↓
阶段 4（Wear）          ←  整模块删除，放在最后避免反复
```

每个阶段完成后单独编译验证，避免一次性改动导致错误定位困难。

---

## 七、最终执行结果（折中方案，2026-09-02）

### 7.1 已删除（保持）

| 模块 | 内容 |
|---|---|
| Google Drive（网络层 + UI） | `data/gdrive/*`（4 文件）、`presentation/gdrive/*`（4 文件）；`AccountsViewModel`/`AccountsScreen`/`CloudStreamSecurity`/`DualPlayerEngine`/`PhoneDirectWatchTransferCoordinator` 的 gdrive 分支；Manifest `GDriveLoginActivity`；12 语种字符串；build.gradle.kts 的 `credentials`/`googleid`/`coroutines.play.services` |
| Android Auto | `automotive_app_desc.xml`、Manifest `gms.car.application`、`MusicService` car 分支 |
| Google Fonts | `montserrat_bold.xml`/`font_certs.xml`/`preloaded_fonts.xml`、Manifest `preloaded_fonts`、build.gradle.kts `ui-text-google-fonts`；`Type.kt` MontserratFamily 改指向本地 `gflex_variable.ttf` |

### 7.2 保留 + GMS 门禁（不再删除）

- **Chromecast**：全套保留（16 文件已恢复），依赖 `mediarouter`+`cast.framework` 恢复，Manifest cast 配置恢复
- **Wear OS**：`wear/` 模块保留
- **新增** `utils/GmsAvailability.kt`：`GoogleApiAvailability.isGooglePlayServicesAvailable() == ConnectionResult.SUCCESS`
- **门禁点（2 处启动路径）**：`WearStatePublisher.publishState`/`clearState` 开头、`CastSyncCoordinator.start` 开头 —— 无 GMS 时直接 return，不触碰 GMS API，杜绝框架级通知

### 7.3 GDrive 数据库残留（保守留表，见 §4.3）

数据库层 `GDriveSongEntity`/`GDriveFolderEntity`/`GDriveDao`/`Song.gdriveFileId`/`source_type=3` 及建表 migration **有意保留**，避免 minSdk 30 下 `DROP COLUMN` 迁移风险。这些是纯本地 Room 代码，无 Google 网络请求。

### 7.4 验收结果

- [x] `./gradlew :app:assembleDebug` 通过（32s）
- [x] 全仓 Google 云端端点仅剩 Gemini ×2（`GeminiModelService.kt`/`GeminiAiClient.kt`，用户保留）
- [ ] 真机验证：无 GMS 环境冷启动无通知（待用户验证）
- [ ] `assembleRelease`（R8，待确认后执行）
