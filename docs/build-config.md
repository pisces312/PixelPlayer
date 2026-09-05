# PixelPlayer 构建配置记录

## 项目信息
- **来源**: github.com/PixelPlayerHQ/PixelPlayer (fork: github.com/pisces312/PixelPlayer)
- **本地路径**: D:\3rd-party-projects\PixelPlayer
- **版本**: 0.7.5-beta (versionCode 9)
- **包名**: com.theveloper.pixelplay (release) / com.theveloper.pixelplay.debug (debug)
- **License**: 2026-05-12 前 MIT；之后专有 (fork MIT 版本)

## 工具链版本
| 工具 | 版本 |
|------|------|
| Gradle | 9.5.1 (wrapper, 腾讯云镜像) |
| AGP | 9.2.1 |
| Kotlin | 2.4.0 |
| KSP | 2.3.9 |
| compileSdk | 37 |
| targetSdk | 37 |
| minSdk | 30 |
| JDK | 21 (JBR) |
| build-tools | 37.0.0 |
| SDK Platform | android-37.0 |

## 镜像配置
- **Gradle 下载**: 腾讯云 `https://mirrors.cloud.tencent.com/gradle/gradle-9.5.1-bin.zip`
- **Maven 仓库**: 阿里云 google/central/gradle-plugin 排第一位 (settings.gradle.kts)

## ABI 配置
- 只构建 `arm64-v8a` (app/build.gradle.kts splits.abi)

## Native 库 (lib/arm64-v8a/)
| 文件 | 大小 | 说明 |
|------|------|------|
| libandroidx.graphics.path.so | 10KB | AndroidX 图形路径库 (ELF ARM64) |
| libffmpegJNI.so | 1428KB | FFmpeg JNI 桥接层，音频解码 (含 avcodec/aac/ffmpeg 符号) |
| libtaglib.so | 1225KB | TagLib 元数据读写库 (支持 FLAC/MP3/M4A 等格式标签) |
| libtdjni.so | 21226KB | TDLib (Telegram Database Library)，Telegram 集成功能，体积最大 |

**native 库总计约 24MB**，占 APK 体积的主要部分之一。

## 混淆配置 (proguard-rules.pro)
- **R8 minify**: 开启 (release)
- **shrinkResources**: 开启 (release)
- **默认 proguard 文件**: proguard-android-optimize.txt
- **主要 keep 规则**:
  - TagLib (`com.kyant.taglib.**`) 和 JAudioTagger (`org.jaudiotagger.**`) 元数据库
  - ExoPlayer FFmpeg 扩展 (`androidx.media3.decoder.ffmpeg.**`)
  - ExoPlayer MIDI 扩展 + JSyn 合成器
  - 数据模型类 (`data.model.**`, `domain.model.**`)
  - Cast framework OptionsProvider
  - Gson TypeToken (备份/恢复)
  - 数据库实体类 (FavoritesEntity, SongEngagementEntity, LyricsEntity 等)
  - Netty channel 类 (反射实例化)
  - Ktor server engine (CIO)
  - TDLib (`org.drinkless.tdlib.**`)
  - Kuromoji (日语分词) + Pinyin4J (拼音)
  - Glance Widget ActionCallback
  - Timber 日志: 剥离 v/d/i 级别日志 (assumenosideeffects)
  - 大量 dontwarn 规则 (java.awt/javax.sound/io.netty/ktor 等)

## 签名配置
- 从环境变量读取: KEY_STORE / KEY_STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
- 签名证书: CN=pisces312, SHA-256 abadebd2fc9523628b5dacfa0fb40f652df0e161b71820c7a4b5a40653ee0b90
- 与 Pinball 项目共用同一签名证书

## Debug/Release 共存
- **debug**: applicationIdSuffix=".debug", versionNameSuffix="-debug", 包名 com.theveloper.pixelplay.debug
- **release**: 包名 com.theveloper.pixelplay
- **debug 图标**: 红色 DBG 角标 (gen_debug_icons.py 生成), app_name "PixelPlayer (Debug)"
- **debug 资源**: app/src/debug/res/ (mipmap-anydpi-v26 adaptive icon XML + 各密度 webp + values/strings.xml)

## 构建命令
```powershell
# Debug
.\gradlew.bat assembleDebug --no-daemon "-Ppixelplay.enableAbiSplits=true"

# Minified Debug（R8 混淆 + 资源压缩，体积 ~92MB vs debug ~185MB，可调试）
.\gradlew.bat assembleMinifiedDebug --no-daemon "-Ppixelplay.enableAbiSplits=true"

# Release (签名)
$env:JAVA_HOME="D:\dev\AndroidStudio\jbr"
$env:GRADLE_USER_HOME="D:\dev\.gradle"
.\gradlew.bat assembleRelease --no-daemon "-Ppixelplay.enableAbiSplits=true"
```

## 构建结果对比
| 类型 | 大小 | dex 数 | res 数 | 说明 |
|------|------|--------|--------|------|
| 官方 release | 62MB | 3 | 1901 | R8 混淆 + shrinkResources |
| 我们 release (无混淆) | 147MB | 9 | 3241 | minify/shrink 关闭 |
| 我们 release (开混淆) | ~65MB (预期) | ~3 | ~1900 | R8 + shrinkResources |
| 我们 debug | 185MB | 9 | 3241 | 无混淆 + debug 信息 |
| 我们 minifiedDebug | 92MB | ~4 | ~2000 | R8 混淆 + shrinkResources，可调试 |

## 改动文件清单 (相对官方源码)
1. settings.gradle.kts - 加阿里云镜像
2. gradle/wrapper/gradle-wrapper.properties - Gradle 下载改为腾讯云镜像
3. app/build.gradle.kts:
   - ABI splits: arm64-v8a + armeabi-v7a → 只 arm64-v8a
   - signingConfigs.release: 从环境变量读取 (原为 vz-pixelplay.jks + keystoreProperties)
   - release buildType: 直接用 release signingConfig (去掉 keystoreFile.exists() 判断)
   - debug buildType: 加 versionNameSuffix="-debug"
4. app/src/debug/res/ - debug 专属图标 + app_name (gen_debug_icons.py 生成)
5. gen_debug_icons.py - debug 图标生成脚本
