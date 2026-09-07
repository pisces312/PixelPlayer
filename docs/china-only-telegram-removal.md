# Telegram 功能移除记录（china-only 分支）

> 分支 `china-only` 从 `af0d81b0`（MiMo commit）起，长期分支，目标：彻底删除 Telegram 与日文罗马音（kuromoji），缩减体积、去除非必需依赖。上游改动同时 merge 到 master 与 china-only。

## 1. 删除范围（14 个源文件）

| 路径 | 说明 |
|---|---|
| `data/telegram/TelegramCacheManager.kt` | 缓存管理 |
| `data/telegram/TelegramClientManager.kt` | TDLib 客户端 |
| `data/telegram/TelegramRepository.kt` | 仓库 |
| `data/telegram/TelegramStreamProxy.kt` | 流代理 |
| `data/image/TelegramCoilFetcher.kt` | Coil 封面 fetcher |
| `data/preferences/TelegramTopicDisplayMode.kt` | 话题显示偏好 |
| `presentation/telegram/auth/TelegramLoginActivity.kt` | 登录 Activity |
| `presentation/telegram/auth/TelegramLoginViewModel.kt` | 登录 VM |
| `presentation/telegram/channel/TelegramChannelSearchSheet.kt` | 频道搜索 |
| `presentation/telegram/channel/TelegramChannelSearchViewModel.kt` | 频道搜索 VM |
| `presentation/telegram/channel/TelegramSongItem.kt` | 歌曲项 |
| `presentation/telegram/dashboard/TelegramDashboardScreen.kt` | 仪表盘 |
| `presentation/telegram/dashboard/TelegramDashboardViewModel.kt` | 仪表盘 VM |
| `res/drawable/telegram.xml` | 图标 |

## 2. 构建层（4 处）

- `app/build.gradle.kts`：移除 `implementation(libs.tdlib)`（flavor 维度已删，无 `globalImplementation`）。
- `AndroidManifest.xml`：移除 `TelegramLoginActivity` 组件声明。
- `app/proguard-rules.pro`：移除 TDLib / kuromoji 的 keep 规则与 `-dontwarn`。
- `gradle/libs.versions.toml`：移除 `tdlib`、`kuromoji.ipadic` 条目。

## 3. 接口 / 枚举 / UI / 偏好清理

- `MusicRepository` 接口 + `MusicRepositoryImpl`：删除 `saveTelegramSongs` / `clearTelegramData` / `getAllTelegramChannels` / `requestTelegramUnifiedSync` / `telegramRepository` 等全部 Telegram 方法；`getSong(songId)` 非数字 id 改为 `flowOf(null)`。
- `ExternalServiceAccount`（AccountsViewModel 内）删除 `TELEGRAM` 枚举值，剩余 `NETEASE` / `QQ_MUSIC` / `NAVIDROME` / `JELLYFIN` 使 `when` 自动穷尽。
- `LyricsUtils.kt`：移除 `import com.atilika.kuromoji.ipadic.Tokenizer`、`MultiLangRomanizer` 的 `kuromojiTokenizer` lazy、`romanizeJapanese()`、`isJapanese()`；**pinyin4j 保留**（中文搜索/罗马音需要）。
- `LyricsRepositoryImpl.kt`：日语分支改为回落原文。
- `LibraryScreen.kt`：删 Telegram `LaunchedEffect`、playlists tab 开关、`extraContent` 话题模式块；`visiblePlaylists` 简化。
- `AccountsScreen.kt` / `AccountsViewModel.kt`：删 Telegram 账号入口。
- `AppModule.kt`：`provideSongMetadataEditor` 去掉 `telegramDao` 参数。
- `UserPreferencesRepository.kt` / `PlaylistPreferencesRepository.kt`：删 Telegram 偏好（`showTelegramCloudPlaylists` / `telegramTopicDisplayMode` 等）。
- 其它：`MainActivity` / `PixelPlayApplication` / `DualPlayerEngine` / `MusicService` / `PhoneDirectWatchTransferCoordinator` / `SongPickerBottomSheet` / `StreamingProviderSheet` 等的 Telegram 入口与 `dagger.Lazy` 守卫清理。
- 测试：`PlayerViewModelTest` / `MusicRepositoryImplTest` / `SyncWorkerTest` 移除 Telegram mock / import / 构造实参。

## 4. 刻意保留（DB 层，关键）

- `TelegramChannelEntity` / `TelegramSongEntity` / `TelegramTopicEntity` / `TelegramDao`
- `SongEntity.SourceType.TELEGRAM` 枚举值
- `MusicDao` 的 telegram 查询
- `app/schemas/` 全部 JSON
- **原因**：删表会触发 Room migration → schema 版本号递增，与 master（同源 v43）备份互通时碰撞。保留后 schema 恒为 v43，与 master 零冲突、零迁移。

## 5. 同源移除：Google Drive 入口

- 本分支亦删除了 `StreamingProviderSheet.kt` 中的 "Google Drive" 云端串流占位行（`enabled = false` 的 Coming soon）。
- GDrive 的 DB 层（`GDriveSongEntity` / `GDriveFolderEntity` / `GDriveDao` / `SongEntity.GDRIVE` / `PixelPlayDatabase` 建表）与 Telegram 表**同理保留**，避免迁移碰撞；GDrive 网络层（`data/gdrive/*`）/ UI（`presentation/gdrive/*`）/ `ExternalServiceAccount.GOOGLE_DRIVE` 早在 degoogle-plan 已删。
- 防御性 `gdrive://` 判断（`LocalArtworkUri` / `PlaybackDispatchStateHolder` / `SongInfoBottomSheetViewModel`）保留，无害。

## 6. 体积收益

- debug APK：约 141MB（无 `libtdjni.so` ~20.7MB + 无 kuromoji 词典 ~12.7MB；`lib/` 仅 `libffmpegJNI.so` / `libtaglib.so` / `libandroidx.graphics.path.so`）。
- release（R8 + shrinkResources + ABI 拆分）：约 27MB。

## 7. 验证

- `compileDebugKotlin` / `compileDebugUnitTestKotlin` / `compileDebugAndroidTestKotlin` 全通过。
- `testDebugUnitTest`：444/444 全绿。
- `assembleDebug` BUILD SUCCESSFUL。

## 8. 实时崩溃教训

- 删除后必须 grep Hilt 生成代码 `Dagger*_SingletonC.java` 中所有 `telegram*Provider.get()` 直接调用点，区分 `DoubleCheck.lazy` 包裹 vs 急切 `.get()`；急切调用在 china-only 下因类不在 APK 触发 `NoClassDefFoundError`。统一改 `dagger.Lazy` + 常量守卫。
